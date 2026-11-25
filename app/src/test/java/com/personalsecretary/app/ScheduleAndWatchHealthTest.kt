package com.personalsecretary.app

import com.personalsecretary.app.data.CalendarEventItem
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.FinancialHygieneReport
import com.personalsecretary.app.data.HealthVitalsSummary
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryTask
import com.personalsecretary.app.data.SsfSummary
import com.personalsecretary.app.data.StepSource
import com.personalsecretary.app.data.TaskCategory
import com.personalsecretary.app.data.TaskPriority
import com.personalsecretary.app.data.TaskRecurrence
import com.personalsecretary.app.schedule.DeviceCalendarReader
import com.personalsecretary.app.schedule.TaskReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleAndWatchHealthTest {

    @Test
    fun computesCalendarTasksWatchVitalsAndGemmaDossier() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 11, 24)
        val nowMillis = LocalDateTime.of(2026, 11, 24, 14, 0).atZone(zone).toInstant().toEpochMilli()

        val todayMeetingStart = LocalDateTime.of(2026, 11, 24, 15, 30).atZone(zone).toInstant().toEpochMilli()
        val todayMeetingEnd = LocalDateTime.of(2026, 11, 24, 16, 30).atZone(zone).toInstant().toEpochMilli()
        val tomorrowStart = LocalDateTime.of(2026, 11, 25, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val tomorrowEnd = LocalDateTime.of(2026, 11, 25, 11, 0).atZone(zone).toInstant().toEpochMilli()

        val events = listOf(
            CalendarEventItem(1L, "Quarterly Budget Review", todayMeetingStart, todayMeetingEnd, location = "Boardroom"),
            CalendarEventItem(2L, "Product Roadmap Sync", tomorrowStart, tomorrowEnd)
        )

        val overdueMillis = LocalDateTime.of(2026, 11, 24, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val laterTodayMillis = LocalDateTime.of(2026, 11, 24, 17, 0).atZone(zone).toInstant().toEpochMilli()

        val tasks = listOf(
            SecretaryTask("t1", "Pay Credit Card Bill", dueAtMillis = overdueMillis, priority = TaskPriority.URGENT, category = TaskCategory.FINANCE, recurrence = TaskRecurrence.MONTHLY),
            SecretaryTask("t2", "Send Client Proposal", dueAtMillis = laterTodayMillis, priority = TaskPriority.HIGH, category = TaskCategory.WORK),
            SecretaryTask("t3", "Completed Item", dueAtMillis = overdueMillis, isCompleted = true)
        )

        val vitals = HealthVitalsSummary(
            lastNightSleepMinutes = 315, // 5h 15m -> triggers short sleep insight
            avgSleepMinutes7d = 410,
            latestHeartRateBpm = 72,
            restingHeartRateBpm = 58,
            latestSpo2Percent = 98.4,
            workoutsThisWeek = 3,
            workoutMinutesThisWeek = 125,
            lastWorkoutTitle = "Brisk Walk"
        )

        val steps = listOf(DailyStepRecord("2026-11-24", 9400L, StepSource.HEALTH_CONNECT))
        val profile = SecretaryProfile(userName = "Client", dailyStepGoal = 10000)

        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = emptyList(),
            ssf = SsfSummary(),
            earnings = emptyList(),
            hygiene = FinancialHygieneReport(),
            steps = steps,
            hourlyStepsByDate = emptyMap(),
            profile = profile,
            today = today,
            healthVitals = vitals,
            calendarEvents = events,
            tasks = tasks,
            nowMillis = nowMillis
        )

        assertEquals(1, snap.todayEvents.size)
        assertEquals(2, snap.upcomingEvents7d.size)
        assertEquals(2, snap.pendingTasks.size)
        assertEquals(1, snap.overdueTasksCount)
        assertEquals(2, snap.dueTodayTasksCount)
        assertTrue(snap.insights.any { it.id == "tasks_overdue" })
        assertTrue(snap.insights.any { it.id == "sleep_short" })

        val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(snap, profile, emptyList())
        assertTrue(dossier.contains("Quarterly Budget Review"))
        assertTrue(dossier.contains("Pay Credit Card Bill"))
        assertTrue(dossier.contains("5h 15m"))
        assertTrue(dossier.contains("Resting HR 58 bpm"))
        assertTrue(dossier.contains("SpO2 98.4%"))
    }

    @Test
    fun computesRecurringTaskNextOccurrence() {
        val zone = ZoneId.of("UTC")
        val base = LocalDateTime.of(2026, 11, 24, 9, 0).atZone(zone).toInstant().toEpochMilli()

        assertNull(TaskReminderScheduler.computeNextOccurrenceMillis(base, TaskRecurrence.NONE, zone))

        val nextDay = TaskReminderScheduler.computeNextOccurrenceMillis(base, TaskRecurrence.DAILY, zone)
        assertNotNull(nextDay)
        assertEquals(LocalDate.of(2026, 11, 25), Instant.ofEpochMilli(nextDay!!).atZone(zone).toLocalDate())

        val nextWeek = TaskReminderScheduler.computeNextOccurrenceMillis(base, TaskRecurrence.WEEKLY, zone)
        assertEquals(LocalDate.of(2026, 12, 1), Instant.ofEpochMilli(nextWeek!!).atZone(zone).toLocalDate())

        val nextMonth = TaskReminderScheduler.computeNextOccurrenceMillis(base, TaskRecurrence.MONTHLY, zone)
        assertEquals(LocalDate.of(2026, 12, 24), Instant.ofEpochMilli(nextMonth!!).atZone(zone).toLocalDate())
    }

    @Test
    fun normalizesUtcAllDayCalendarEventsForPositiveOffsetTimezones() {
        val nepalZone = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        val today = LocalDate.of(2026, 11, 25)
        val todayStart = today.atStartOfDay(nepalZone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(nepalZone).toInstant().toEpochMilli()

        // Yesterday's all-day holiday stored in UTC (Nov 24 00:00 UTC -> Nov 25 00:00 UTC = 05:45 NPT)
        val yestBeginUtc = LocalDate.of(2026, 11, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val yestEndUtc = LocalDate.of(2026, 11, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val (normYestStart, normYestEnd) = DeviceCalendarReader.normalizeEventBounds(yestBeginUtc, yestEndUtc, true, nepalZone)

        // Must NOT spill into Nov 25 in Nepal timezone!
        assertFalse(normYestEnd > todayStart)
        assertEquals(todayStart, normYestEnd)
        assertEquals(LocalDate.of(2026, 11, 24), Instant.ofEpochMilli(normYestStart).atZone(nepalZone).toLocalDate())

        // Today's all-day event stored in UTC (Nov 25 00:00 UTC -> Nov 26 00:00 UTC)
        val todayBeginUtc = LocalDate.of(2026, 11, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val todayEndUtc = LocalDate.of(2026, 11, 26).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val (normTodayStart, normTodayEnd) = DeviceCalendarReader.normalizeEventBounds(todayBeginUtc, todayEndUtc, true, nepalZone)

        assertEquals(todayStart, normTodayStart)
        assertEquals(tomorrowStart, normTodayEnd)
        assertTrue(normTodayStart < tomorrowStart && normTodayEnd > todayStart)
    }
}
