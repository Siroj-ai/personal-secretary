package com.personalsecretary.app.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.data.StepSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StepPermissionStatus(
    val healthConnectAvailable: Boolean = false,
    val healthConnectStepsGranted: Boolean = false,
    val healthConnectBackgroundGranted: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    val hardwareStepSensorAvailable: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val statusDetail: String = ""
)

class OnePlusStepTracker(
    private val context: Context,
    private val database: SecretaryDatabase,
    private val scope: CoroutineScope
) : SensorEventListener {

    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _permissionStatus = MutableStateFlow(StepPermissionStatus())
    val permissionStatus: StateFlow<StepPermissionStatus> = _permissionStatus.asStateFlow()

    private val _liveHardwareStepsToday = MutableStateFlow<Long?>(null)
    val liveHardwareStepsToday: StateFlow<Long?> = _liveHardwareStepsToday.asStateFlow()

    @Volatile
    private var lastCumulativeSensorValue: Long? = null
    private var sensorRegistered = false

    val healthConnectReadPermissions: Set<String> = buildSet {
        add(HealthPermission.getReadPermission(StepsRecord::class))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    val coreHealthConnectPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    fun isHealthConnectAvailable(): Boolean {
        return runCatching {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }.getOrDefault(false)
    }

    fun isActivityRecognitionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    suspend fun refreshPermissionStatus(): StepPermissionStatus = withContext(Dispatchers.IO) {
        val hcAvail = isHealthConnectAvailable()
        var stepsGranted = false
        var bgGranted = false

        if (hcAvail) {
            runCatching {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                stepsGranted = granted.contains(HealthPermission.getReadPermission(StepsRecord::class))
                bgGranted = granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }

        val actRec = isActivityRecognitionGranted()
        val hasSensor = stepCounterSensor != null
        val batteryOk = isIgnoringBatteryOptimizations()

        val detail = when {
            stepsGranted -> "Health Connect active" + if (hasSensor && actRec) " + OP 13 live pedometer" else ""
            hasSensor && actRec -> "OP 13 hardware pedometer active (grant Health Connect for multi-day history)"
            hcAvail -> "Grant Health Connect or Physical Activity permission to track steps"
            else -> "Grant Physical Activity permission for OnePlus 13 hardware step sensor"
        }

        val st = StepPermissionStatus(
            healthConnectAvailable = hcAvail,
            healthConnectStepsGranted = stepsGranted,
            healthConnectBackgroundGranted = bgGranted,
            activityRecognitionGranted = actRec,
            hardwareStepSensorAvailable = hasSensor,
            batteryOptimizationIgnored = batteryOk,
            statusDetail = detail
        )
        _permissionStatus.value = st
        st
    }

    fun startHardwareSensorIfPermitted() {
        if (sensorRegistered) return
        val sensor = stepCounterSensor ?: return
        if (!isActivityRecognitionGranted()) return
        runCatching {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            sensorRegistered = true
        }
    }

    fun stopHardwareSensor() {
        if (!sensorRegistered) return
        runCatching { sensorManager.unregisterListener(this) }
        sensorRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val cumulativeSinceBoot = event.values.firstOrNull()?.toLong() ?: return
        lastCumulativeSensorValue = cumulativeSinceBoot
        val todayIso = LocalDate.now().format(isoFmt)

        scope.launch(Dispatchers.IO) {
            val existingToday = database.loadStepRecords(limitDays = 7).firstOrNull { it.dateIso == todayIso }
            val savedDate = database.getStringValue(KEY_SENSOR_DATE)
            val savedBaseline = database.getStringValue(KEY_SENSOR_BASELINE)?.toLongOrNull()

            // Anchor hardware baseline cleanly against any higher Health Connect / stored count for today
            val storedStepsToday = existingToday?.steps ?: 0L
            val baseline = if (savedDate == todayIso && savedBaseline != null && cumulativeSinceBoot >= savedBaseline) {
                val rawDelta = cumulativeSinceBoot - savedBaseline
                if (storedStepsToday > rawDelta) {
                    val reanchored = (cumulativeSinceBoot - storedStepsToday).coerceAtLeast(0L)
                    database.putStringValue(KEY_SENSOR_BASELINE, reanchored.toString())
                    reanchored
                } else {
                    savedBaseline
                }
            } else {
                val initialBaseline = (cumulativeSinceBoot - storedStepsToday).coerceAtLeast(0L)
                database.putStringValue(KEY_SENSOR_DATE, todayIso)
                database.putStringValue(KEY_SENSOR_BASELINE, initialBaseline.toString())
                initialBaseline
            }

            val liveTodaySteps = (cumulativeSinceBoot - baseline).coerceAtLeast(storedStepsToday)
            _liveHardwareStepsToday.value = liveTodaySteps

            if (existingToday == null || liveTodaySteps > existingToday.steps) {
                val source = if (existingToday?.source == StepSource.HEALTH_CONNECT) {
                    StepSource.HEALTH_CONNECT
                } else {
                    StepSource.HARDWARE_SENSOR
                }
                database.upsertStepRecords(
                    listOf(
                        DailyStepRecord(
                            dateIso = todayIso,
                            steps = liveTodaySteps,
                            source = source,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Reads daily aggregated steps for the last [days] days from Android Health Connect
     * using AggregateGroupByPeriodRequest (local time, single IPC call) with per-day fallback.
     */
    suspend fun syncStepsFromHealthConnect(days: Int = 14): Result<List<DailyStepRecord>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(isHealthConnectAvailable()) { "Health Connect is not available on this device." }
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                require(granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                    "Health Connect Steps permission has not been granted yet."
                }

                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val todayIso = today.format(isoFmt)
                val startDay = today.minusDays((days - 1).toLong())
                val nowLocal = LocalDateTime.now(zone)
                val existingByDate = database.loadStepRecords(limitDays = days + 7).associateBy { it.dateIso }

                val hcTotalsByDate = mutableMapOf<String, Long>()

                // Preferred single-call local-time daily bucket aggregation
                val groupedResult = runCatching {
                    val req = AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startDay.atStartOfDay(), nowLocal),
                        timeRangeSlicer = Period.ofDays(1)
                    )
                    client.aggregateGroupByPeriod(req)
                }

                if (groupedResult.isSuccess) {
                    for (bucket in groupedResult.getOrDefault(emptyList())) {
                        val dayIso = bucket.startTime.toLocalDate().format(isoFmt)
                        val count = bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L
                        hcTotalsByDate[dayIso] = maxOf(hcTotalsByDate[dayIso] ?: 0L, count)
                    }
                } else {
                    // Fallback to per-day Instant aggregation if OEM provider rejects period grouping
                    val nowInstant = java.time.Instant.now()
                    for (offset in (days - 1) downTo 0) {
                        val day = today.minusDays(offset.toLong())
                        val startInstant = day.atStartOfDay(zone).toInstant()
                        val endInstant = if (offset == 0) nowInstant else day.plusDays(1).atStartOfDay(zone).toInstant()
                        if (!endInstant.isAfter(startInstant)) continue
                        val req = AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant)
                        )
                        val resp = client.aggregate(req)
                        hcTotalsByDate[day.format(isoFmt)] = resp[StepsRecord.COUNT_TOTAL] ?: 0L
                    }
                }

                val records = mutableListOf<DailyStepRecord>()
                for (offset in (days - 1) downTo 0) {
                    val iso = today.minusDays(offset.toLong()).format(isoFmt)
                    val hcSteps = hcTotalsByDate[iso] ?: 0L
                    val existing = existingByDate[iso]

                    val mergedSteps = if (offset == 0) {
                        val live = _liveHardwareStepsToday.value ?: 0L
                        val best = maxOf(hcSteps, live, existing?.steps ?: 0L)
                        // Re-anchor hardware baseline if Health Connect jumped ahead of live hardware delta
                        val latestCum = lastCumulativeSensorValue
                        if (latestCum != null && hcSteps > live) {
                            database.putStringValue(KEY_SENSOR_DATE, todayIso)
                            database.putStringValue(KEY_SENSOR_BASELINE, (latestCum - best).coerceAtLeast(0L).toString())
                            _liveHardwareStepsToday.value = best
                        }
                        best
                    } else {
                        if (existing?.source == StepSource.MANUAL) maxOf(hcSteps, existing.steps) else hcSteps
                    }

                    val source = when {
                        hcSteps > 0L -> StepSource.HEALTH_CONNECT
                        existing?.source == StepSource.MANUAL && mergedSteps > 0L -> StepSource.MANUAL
                        mergedSteps > 0L -> StepSource.HARDWARE_SENSOR
                        else -> StepSource.HEALTH_CONNECT
                    }

                    records += DailyStepRecord(
                        dateIso = iso,
                        steps = mergedSteps,
                        source = source,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                }

                database.upsertStepRecords(records)
                records
            }
        }

    fun createAppSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createHealthConnectSettingsIntent(): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            "android.health.connect.action.HEALTH_HOME_SETTINGS"
        } else {
            HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
        }
        return Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val KEY_SENSOR_DATE = "hw_step_sensor_date"
        private const val KEY_SENSOR_BASELINE = "hw_step_sensor_baseline"
    }
}
