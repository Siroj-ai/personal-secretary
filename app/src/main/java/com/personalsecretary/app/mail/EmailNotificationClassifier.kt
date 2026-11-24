package com.personalsecretary.app.mail

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.personalsecretary.app.data.EmailAlertItem
import com.personalsecretary.app.data.EmailAlertTag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.MessageDigest
import java.util.Locale

/**
 * Pure Kotlin allowlist, privacy filter (OTP/2FA blocker), and local email classifier.
 * Keeps all email alert parsing deterministic, unit-testable, and 100% on-device.
 */
object EmailNotificationClassifier {

    private val allowedEmailPackages: Map<String, String> = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.google.android.gm.lite" to "Gmail Go",
        "com.microsoft.office.outlook" to "Outlook",
        "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
        "ch.protonmail.android" to "Proton Mail",
        "com.samsung.android.email.provider" to "Samsung Email",
        "com.readdle.spark" to "Spark",
        "com.fsck.k9" to "K-9 Mail",
        "net.thunderbird.android" to "Thunderbird",
        "me.bluemail.mail" to "BlueMail",
        "com.zoho.mail" to "Zoho Mail",
        "com.fastmail.app" to "Fastmail",
        "com.oneplus.mail" to "Mail",
        "com.coloros.mail" to "Mail"
    )

    private val otpPrivacyRegex = Regex(
        """\b(otp|one[\s-]?time\s*(?:password|passcode|code)|verification\s*code|security\s*code|2fa|two[\s-]?factor|login\s*code|sign[\s-]?in\s*code|passcode\s*is)\b""",
        RegexOption.IGNORE_CASE
    )

    private val genericSummaryRegex = Regex(
        """^\s*(\d+\s+new\s+(?:messages?|emails?)|syncing\s+mail|checking\s+for\s+mail|downloading\s+attachment|sending\s+message).*$""",
        RegexOption.IGNORE_CASE
    )

    fun isAllowedEmailPackage(packageName: String): Boolean =
        allowedEmailPackages.containsKey(packageName)

    fun resolveAppLabel(packageName: String): String =
        allowedEmailPackages[packageName] ?: "Email"

    /**
     * Parses a raw Android email notification into a clean [EmailAlertItem],
     * or returns null if the package isn't an email client, if it's a summary/status notification,
     * or if it contains an OTP/2FA verification code (blocked for security).
     */
    fun parseEmailNotification(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        postTimeMillis: Long
    ): EmailAlertItem? {
        if (!isAllowedEmailPackage(packageName)) return null

        val cleanTitle = title.trim()
        val cleanText = text.trim()
        val cleanBig = bigText.trim()

        if (cleanTitle.isEmpty() && cleanText.isEmpty() && cleanBig.isEmpty()) return null
        if (genericSummaryRegex.matches(cleanTitle) || genericSummaryRegex.matches(cleanText)) return null

        // Extract sender, subject, and preview cleanly (Gmail/Outlook commonly put Sender in title, Subject\nBody in bigText)
        val sender: String
        val subject: String
        val snippet: String

        if (cleanBig.contains('\n')) {
            val lines = cleanBig.lines().map { it.trim() }.filter { it.isNotEmpty() }
            sender = cleanTitle.ifBlank { subText.trim().ifBlank { resolveAppLabel(packageName) } }
            subject = lines.firstOrNull().orEmpty().ifBlank { cleanText }
            snippet = lines.drop(1).joinToString(" ").ifBlank { cleanText }
        } else {
            sender = cleanTitle.ifBlank { subText.trim().ifBlank { resolveAppLabel(packageName) } }
            subject = cleanText.ifBlank { cleanBig }
            snippet = if (cleanBig.length > cleanText.length) cleanBig else ""
        }

        if (subject.isBlank() && snippet.isBlank()) return null

        val combined = "$sender $subject $snippet"
        // Security shield: never store OTPs / 2FA / login codes
        if (otpPrivacyRegex.containsMatchIn(combined)) return null

        val lower = combined.lowercase(Locale.US)
        val tag = classifyTag(lower)
        val isUrgent = detectUrgency(lower, tag)

        // Deterministic deduplication ID bucketed per 10-minute window so notification re-posts don't duplicate
        val timeBucket = postTimeMillis / (10L * 60L * 1000L)
        val stableId = "mail_" + sha256Short("$packageName|$sender|$subject|$timeBucket")

        return EmailAlertItem(
            id = stableId,
            packageName = packageName,
            appLabel = resolveAppLabel(packageName),
            sender = sender.take(120),
            subject = subject.take(200),
            snippet = snippet.take(360),
            receivedAtMillis = postTimeMillis,
            tag = tag,
            isUrgent = isUrgent,
            isRead = false
        )
    }

    private fun classifyTag(lower: String): EmailAlertTag {
        return when {
            listOf(
                "invoice", "statement", "credit card", "debit", "payment due", "bill due",
                "due date", "bank", "transaction", "salary", "esewa", "khalti", "fonepay",
                "connectips", "receipt", "subscription", "charged", "refund", "tax"
            ).any { lower.contains(it) } -> EmailAlertTag.BILL_FINANCE

            listOf(
                "invitation:", "invite", "meeting", "calendar", "google meet", "zoom",
                "microsoft teams", "flight", "itinerary", "booking confirmed", "appointment",
                "rescheduled", "webinar"
            ).any { lower.contains(it) } -> EmailAlertTag.MEETING_SCHEDULE

            listOf(
                "urgent", "deadline", "action required", "please review", "approval",
                "proposal", "report", "follow up", "follow-up", "client", "project",
                "contract", "deliverable", "asap", "reminder"
            ).any { lower.contains(it) } -> EmailAlertTag.WORK_ACTION

            else -> EmailAlertTag.GENERAL
        }
    }

    private fun detectUrgency(lower: String, tag: EmailAlertTag): Boolean {
        if (listOf("urgent", "asap", "action required", "overdue", "final notice", "payment due", "due today", "deadline").any { lower.contains(it) }) {
            return true
        }
        return tag == EmailAlertTag.BILL_FINANCE && (lower.contains("due") || lower.contains("failed") || lower.contains("declined"))
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * In-process reactive bus so the UI refreshes immediately whenever a new email notification arrives.
 */
object EmailNotificationEvents {
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    fun notifyUpdated() {
        _updates.tryEmit(Unit)
    }
}

object EmailNotificationAccessHelper {
    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    fun createNotificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
