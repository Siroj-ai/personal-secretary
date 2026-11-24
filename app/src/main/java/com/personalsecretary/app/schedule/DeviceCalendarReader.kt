package com.personalsecretary.app.schedule

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.personalsecretary.app.data.CalendarEventItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads upcoming meetings and schedules directly from Android's synced Calendar Provider
 * (Google Calendar, Outlook, Work Exchange, etc.) completely on-device without external OAuth.
 */
class DeviceCalendarReader(private val context: Context) {

    fun isCalendarPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun loadUpcomingEvents(daysAhead: Int = 7): List<CalendarEventItem> = withContext(Dispatchers.IO) {
        if (!isCalendarPermissionGranted()) return@withContext emptyList()

        runCatching {
            val zone = ZoneId.systemDefault()
            val startMillis = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = LocalDate.now(zone).plusDays(daysAhead.toLong() + 1L)
                .atStartOfDay(zone).toInstant().toEpochMilli()

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )

            val out = mutableListOf<CalendarEventItem>()
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    out += CalendarEventItem(
                        id = c.getLong(0),
                        title = title,
                        startMillis = c.getLong(2),
                        endMillis = c.getLong(3),
                        allDay = c.getInt(4) == 1,
                        location = c.getString(5)?.trim().orEmpty(),
                        calendarName = c.getString(6)?.trim().orEmpty()
                    )
                }
            }
            out.distinctBy { "${it.id}_${it.startMillis}" }.take(60)
        }.getOrDefault(emptyList())
    }
}
