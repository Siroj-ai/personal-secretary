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
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.HealthVitalsSummary
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.data.StepSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StepPermissionStatus(
    val healthConnectAvailable: Boolean = false,
    val healthConnectStepsGranted: Boolean = false,
    val healthConnectBackgroundGranted: Boolean = false,
    val watchVitalsGranted: Boolean = false,
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
    private val liveTicksLock = Any()
    private var liveTicksDateIso: String = ""
    private val liveTicksByHour = LongArray(24)

    val watchVitalReadPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    val healthConnectReadPermissions: Set<String> = buildSet {
        add(HealthPermission.getReadPermission(StepsRecord::class))
        addAll(watchVitalReadPermissions)
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
        var watchGranted = false

        if (hcAvail) {
            runCatching {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                stepsGranted = granted.contains(HealthPermission.getReadPermission(StepsRecord::class))
                bgGranted = granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                watchGranted = watchVitalReadPermissions.any { granted.contains(it) }
            }
        }

        val actRec = isActivityRecognitionGranted()
        val hasSensor = stepCounterSensor != null
        val batteryOk = isIgnoringBatteryOptimizations()

        val detail = when {
            stepsGranted && watchGranted -> "Health Connect (Steps + Watch Vitals)" + if (hasSensor && actRec) " + OP 13 pedometer" else ""
            stepsGranted -> "Health Connect Steps active" + if (hasSensor && actRec) " + OP 13 live pedometer" else ""
            hasSensor && actRec -> "OP 13 hardware pedometer active (grant Health Connect for watch & multi-day history)"
            hcAvail -> "Grant Health Connect or Physical Activity permission to track steps & watch vitals"
            else -> "Grant Physical Activity permission for OnePlus 13 hardware step sensor"
        }

        val st = StepPermissionStatus(
            healthConnectAvailable = hcAvail,
            healthConnectStepsGranted = stepsGranted,
            healthConnectBackgroundGranted = bgGranted,
            watchVitalsGranted = watchGranted,
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
        val currentHour = LocalTime.now().hour.coerceIn(0, 23)

        scope.launch(Dispatchers.IO) {
            val existingToday = database.loadStepRecords(limitDays = 7).firstOrNull { it.dateIso == todayIso }
            val savedDate = database.getStringValue(KEY_SENSOR_DATE)
            val savedBaseline = database.getStringValue(KEY_SENSOR_BASELINE)?.toLongOrNull()

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
            val deltaAdded = (liveTodaySteps - storedStepsToday).coerceAtLeast(0L)
            _liveHardwareStepsToday.value = liveTodaySteps

            if (existingToday == null || liveTodaySteps > existingToday.steps) {
                val source = when (existingToday?.source) {
                    StepSource.HEALTH_CONNECT -> StepSource.HEALTH_CONNECT
                    StepSource.MANUAL -> StepSource.MANUAL
                    else -> StepSource.HARDWARE_SENSOR
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

                // Reflect newly ticked live steps in the current hour bucket for Samsung Health-style hourly chart
                if (deltaAdded > 0L) {
                    synchronized(liveTicksLock) {
                        if (liveTicksDateIso != todayIso) {
                            liveTicksDateIso = todayIso
                            liveTicksByHour.fill(0L)
                        }
                        liveTicksByHour[currentHour] = liveTicksByHour[currentHour] + deltaAdded
                    }
                    val hourlyMap = database.loadHourlySteps().toMutableMap()
                    val todayHours = (hourlyMap[todayIso] ?: List(24) { 0L }).toMutableList()
                    while (todayHours.size < 24) todayHours.add(0L)
                    todayHours[currentHour] = todayHours[currentHour] + deltaAdded
                    hourlyMap[todayIso] = todayHours
                    database.saveHourlySteps(hourlyMap)
                }
            }
        }
    }

    /**
     * Persists a manual step override for today and re-anchors the live hardware pedometer baseline
     * so future sensor ticks increment cleanly from this value instead of snapping back.
     */
    suspend fun anchorManualStepsToday(stepsToday: Long) = withContext(Dispatchers.IO) {
        val safe = stepsToday.coerceAtLeast(0L)
        val todayIso = LocalDate.now().format(isoFmt)
        database.upsertStepRecords(
            listOf(DailyStepRecord(todayIso, safe, StepSource.MANUAL, System.currentTimeMillis()))
        )
        database.putStringValue(KEY_SENSOR_DATE, todayIso)
        val latestCum = lastCumulativeSensorValue
        if (latestCum != null) {
            database.putStringValue(KEY_SENSOR_BASELINE, (latestCum - safe).coerceAtLeast(0L).toString())
        } else {
            database.putStringValue(KEY_SENSOR_BASELINE, "")
        }
        _liveHardwareStepsToday.value = safe
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Reads daily aggregated steps (last [days] days) AND 24-hour buckets (last 7 days)
     * from Android Health Connect for the Samsung Health-style steps experience.
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
                        val best = if (existing?.source == StepSource.MANUAL) {
                            maxOf(existing.steps, live)
                        } else {
                            maxOf(hcSteps, live, existing?.steps ?: 0L)
                        }
                        val latestCum = lastCumulativeSensorValue
                        if (latestCum != null && existing?.source != StepSource.MANUAL && hcSteps > live) {
                            database.putStringValue(KEY_SENSOR_DATE, todayIso)
                            database.putStringValue(KEY_SENSOR_BASELINE, (latestCum - best).coerceAtLeast(0L).toString())
                            _liveHardwareStepsToday.value = best
                        }
                        best
                    } else {
                        if (existing?.source == StepSource.MANUAL) maxOf(hcSteps, existing.steps) else hcSteps
                    }

                    val source = when {
                        existing?.source == StepSource.MANUAL && mergedSteps > 0L -> StepSource.MANUAL
                        hcSteps > 0L -> StepSource.HEALTH_CONNECT
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

                // Sync 24-hour buckets for the last 7 days in 1 clean Health Connect duration call
                syncHourlyBucketsLast7Days(client, today, zone)

                // Sync watch-ready vitals (Sleep, HR, Resting HR, SpO2, Workouts) for any granted permissions
                syncWatchVitalsInternal(client, granted, today, zone)

                records
            }
        }

    /**
     * Syncs Sleep, Heart Rate, Resting HR, SpO2, and Exercise Sessions from Android Health Connect
     * whenever written by a paired watch/band (OnePlus Watch / OHealth, Fitbit, Amazfit Zepp, Xiaomi, Garmin).
     */
    suspend fun syncWatchVitalsFromHealthConnect(): Result<HealthVitalsSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(isHealthConnectAvailable()) { "Health Connect is not available." }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val zone = ZoneId.systemDefault()
            syncWatchVitalsInternal(client, granted, LocalDate.now(zone), zone)
        }
    }

    private suspend fun syncWatchVitalsInternal(
        client: HealthConnectClient,
        granted: Set<String>,
        today: LocalDate,
        zone: ZoneId
    ): HealthVitalsSummary {
        val now = java.time.Instant.now()
        val start7d = today.minusDays(6).atStartOfDay(zone).toInstant()
        val start36h = now.minus(Duration.ofHours(36))
        val start48h = now.minus(Duration.ofHours(48))

        var lastNightSleepMin = 0
        var avgSleep7dMin = 0
        var latestHr: Int? = null
        var restingHr: Int? = null
        var latestSpo2: Double? = null
        var workoutsCount = 0
        var workoutMins = 0
        var lastWorkoutTitle = ""

        if (granted.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
            runCatching {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start7d, now)
                    )
                )
                val byEndDay = mutableMapOf<LocalDate, Long>()
                for (rec in resp.records) {
                    val mins = Duration.between(rec.startTime, rec.endTime).toMinutes().coerceIn(0L, 1440L)
                    if (mins <= 0L) continue
                    val day = rec.endTime.atZone(zone).toLocalDate()
                    byEndDay[day] = (byEndDay[day] ?: 0L) + mins
                }
                lastNightSleepMin = (byEndDay[today] ?: byEndDay[today.minusDays(1)] ?: 0L).toInt()
                if (byEndDay.isNotEmpty()) {
                    avgSleep7dMin = byEndDay.values.average().toInt()
                }
            }
        }

        if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            runCatching {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start36h, now)
                    )
                )
                val latestSample = resp.records
                    .flatMap { it.samples }
                    .maxByOrNull { it.time }
                latestHr = latestSample?.beatsPerMinute?.toInt()
            }
        }

        if (granted.contains(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) {
            runCatching {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start7d, now)
                    )
                )
                restingHr = resp.records.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
            }
        }

        if (granted.contains(HealthPermission.getReadPermission(OxygenSaturationRecord::class))) {
            runCatching {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start48h, now)
                    )
                )
                latestSpo2 = resp.records.maxByOrNull { it.time }?.percentage?.value
            }
        }

        if (granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
            runCatching {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start7d, now)
                    )
                )
                workoutsCount = resp.records.size
                workoutMins = resp.records.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes().coerceIn(0L, 600L)
                }.toInt()
                lastWorkoutTitle = resp.records.maxByOrNull { it.endTime }?.title?.trim().orEmpty()
                    .ifBlank { if (workoutsCount > 0) "Workout" else "" }
            }
        }

        val summary = HealthVitalsSummary(
            lastNightSleepMinutes = lastNightSleepMin,
            avgSleepMinutes7d = avgSleep7dMin,
            latestHeartRateBpm = latestHr,
            restingHeartRateBpm = restingHr,
            latestSpo2Percent = latestSpo2,
            workoutsThisWeek = workoutsCount,
            workoutMinutesThisWeek = workoutMins,
            lastWorkoutTitle = lastWorkoutTitle,
            syncedAtMillis = System.currentTimeMillis()
        )
        database.saveHealthVitals(summary)
        return summary
    }

    private suspend fun syncHourlyBucketsLast7Days(
        client: HealthConnectClient,
        today: LocalDate,
        zone: ZoneId
    ) {
        val todayIso = today.format(isoFmt)
        val start7 = today.minusDays(6).atStartOfDay(zone).toInstant()
        val endNow = java.time.Instant.now()
        if (!endNow.isAfter(start7)) return

        val hourlyByDate = mutableMapOf<String, LongArray>()
        for (offset in 6 downTo 0) {
            hourlyByDate[today.minusDays(offset.toLong()).format(isoFmt)] = LongArray(24)
        }

        runCatching {
            val req = AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start7, endNow),
                timeRangeSlicer = Duration.ofHours(1)
            )
            val buckets = client.aggregateGroupByDuration(req)
            for (b in buckets) {
                val count = b.result[StepsRecord.COUNT_TOTAL] ?: 0L
                if (count <= 0L) continue
                val zdt = b.startTime.atZone(zone)
                val dayIso = zdt.toLocalDate().format(isoFmt)
                val hour = zdt.hour.coerceIn(0, 23)
                val arr = hourlyByDate[dayIso] ?: continue
                arr[hour] = arr[hour] + count
            }
        }

        // Overlay only real incremental hardware-sensor ticks captured this session;
        // never fabricate a giant moving spike in the current hour from manual overrides.
        val todayArr = hourlyByDate[todayIso]
        if (todayArr != null) {
            synchronized(liveTicksLock) {
                if (liveTicksDateIso == todayIso) {
                    for (h in 0 until 24) {
                        if (liveTicksByHour[h] > todayArr[h]) {
                            todayArr[h] = liveTicksByHour[h]
                        }
                    }
                }
            }
        }

        database.saveHourlySteps(hourlyByDate.mapValues { it.value.toList() })
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
