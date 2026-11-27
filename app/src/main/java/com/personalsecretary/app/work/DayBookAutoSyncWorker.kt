package com.personalsecretary.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personalsecretary.app.data.DayBookSyncClient
import com.personalsecretary.app.data.SecretaryDatabase
import com.personalsecretary.app.data.SecretaryProfile
import java.util.concurrent.TimeUnit

/**
 * Silent hourly background sync that pulls fresh Google Sheets Day Book rows
 * whenever the device has network connectivity and auto-sync is enabled.
 */
class DayBookAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = SecretaryDatabase(applicationContext)
        val profile = db.loadProfile()
        syncDayBookIfConfigured(db, profile)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "personal_secretary_daybook_auto_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<DayBookAutoSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun hasRemoteSource(profile: SecretaryProfile): Boolean =
            profile.appsScriptUrl.startsWith("https://") || profile.spreadsheetId.isNotBlank()

        suspend fun syncDayBookIfConfigured(
            db: SecretaryDatabase,
            profile: SecretaryProfile,
            minIntervalMs: Long = 0L
        ): Boolean {
            if (!profile.autoSyncDayBook || !hasRemoteSource(profile)) return false
            if (minIntervalMs > 0L) {
                val lastSync = db.loadLastDayBookSyncMillis()
                if (System.currentTimeMillis() - lastSync < minIntervalMs) return false
            }
            val client = DayBookSyncClient()
            val result = when {
                profile.appsScriptUrl.startsWith("https://") ->
                    client.syncFromAppsScript(profile.appsScriptUrl)
                profile.spreadsheetId.isNotBlank() ->
                    client.syncFromPublicSheetCsv(profile.spreadsheetId)
                else -> return false
            }
            val bundle = result.getOrNull() ?: return false
            db.saveFinancialBundle(
                transactions = bundle.transactions,
                ssf = bundle.ssfSummary,
                earnings = bundle.earnings,
                abbreviations = bundle.abbreviations,
                hygiene = bundle.hygieneReport
            )
            return true
        }
    }
}
