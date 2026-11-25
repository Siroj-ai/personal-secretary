package com.personalsecretary.app

import com.personalsecretary.app.data.EmailAlertTag
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.FinancialHygieneReport
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SsfSummary
import com.personalsecretary.app.mail.EmailNotificationClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmailNotificationClassifierTest {

    @Test
    fun blocksNonEmailAppsOtpsAndSyncSummaries() {
        // Non-email app must be ignored
        val whatsapp = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.whatsapp",
            title = "Alex",
            text = "Credit card bill due tomorrow",
            bigText = "",
            subText = "",
            postTimeMillis = 1_700_000_000_000L
        )
        assertNull(whatsapp)

        // OTP / 2FA code inside Gmail must be blocked by privacy shield
        val otpMail = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.google.android.gm",
            title = "Bank Security",
            text = "Your one-time password (OTP) is 482910",
            bigText = "Your verification code for login is 482910. Do not share.",
            subText = "",
            postTimeMillis = 1_700_000_000_000L
        )
        assertNull(otpMail)

        // Generic stacked summary notification must be ignored
        val summary = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.google.android.gm",
            title = "3 new messages",
            text = "Inbox",
            bigText = "",
            subText = "",
            postTimeMillis = 1_700_000_000_000L
        )
        assertNull(summary)
    }

    @Test
    fun classifiesBillsMeetingsAndWorkEmailsAndFeedsDossier() {
        val now = 1_795_520_000_000L
        val bill = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.google.android.gm",
            title = "City Bank Cards",
            text = "Monthly Credit Card Statement",
            bigText = "Monthly Credit Card Statement\nPayment due by Nov 28 to avoid late charges.",
            subText = "",
            postTimeMillis = now
        )
        assertNotNull(bill)
        assertEquals(EmailAlertTag.BILL_FINANCE, bill!!.tag)
        assertTrue(bill.isUrgent)
        assertEquals("Gmail", bill.appLabel)

        val meeting = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.microsoft.office.outlook",
            title = "Operations Team",
            text = "Invitation: Project Review on Google Meet",
            bigText = "",
            subText = "",
            postTimeMillis = now - 60_000L
        )
        assertNotNull(meeting)
        assertEquals(EmailAlertTag.MEETING_SCHEDULE, meeting!!.tag)
        assertFalse(meeting.isUrgent)
        assertEquals("Outlook", meeting.appLabel)

        val work = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.google.android.gm",
            title = "Client Lead",
            text = "Action Required: Q4 Deliverable Proposal",
            bigText = "",
            subText = "",
            postTimeMillis = now - 120_000L
        )
        assertNotNull(work)
        assertEquals(EmailAlertTag.WORK_ACTION, work!!.tag)
        assertTrue(work.isUrgent)

        val edisonBooking = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.easilydo.mail",
            title = "Travel Desk",
            text = "Reminder: Hotel booking confirmation action required",
            bigText = "Reminder: Hotel booking confirmation action required\nPlease review before deadline.",
            subText = "work@example.com",
            postTimeMillis = now - 30_000L
        )
        assertNotNull(edisonBooking)
        assertEquals("Edison Mail", edisonBooking!!.appLabel)
        assertEquals(EmailAlertTag.WORK_ACTION, edisonBooking.tag)
        assertTrue(edisonBooking.isUrgent)

        val edisonOtp = EmailNotificationClassifier.parseEmailNotification(
            packageName = "com.easilydo.mail",
            title = "Security Alert",
            text = "Your verification code is 991204",
            bigText = "",
            subText = "",
            postTimeMillis = now
        )
        assertNull(edisonOtp)

        val profile = SecretaryProfile(userName = "Client")
        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = emptyList(),
            ssf = SsfSummary(),
            earnings = emptyList(),
            hygiene = FinancialHygieneReport(),
            steps = emptyList(),
            hourlyStepsByDate = emptyMap(),
            profile = profile,
            today = LocalDate.of(2026, 11, 24),
            emailAlerts = listOf(bill, meeting, work, edisonBooking),
            nowMillis = now
        )

        assertEquals(4, snap.unreadEmailsCount)
        assertEquals(3, snap.urgentEmailsCount)
        assertTrue(snap.insights.any { it.id == "emails_urgent" })

        val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(snap, profile, emptyList())
        assertTrue(dossier.contains("RECENT EMAIL ALERTS"))
        assertTrue(dossier.contains("Monthly Credit Card Statement"))
        assertTrue(dossier.contains("Action Required: Q4 Deliverable Proposal"))
        assertTrue(dossier.contains("Travel Desk"))
    }
}
