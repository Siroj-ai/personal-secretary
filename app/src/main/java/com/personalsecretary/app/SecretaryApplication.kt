package com.personalsecretary.app

import android.app.Application
import com.personalsecretary.app.work.DailyBriefingWorker
import com.personalsecretary.app.work.DayBookAutoSyncWorker

class SecretaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DailyBriefingWorker.schedule(this)
        DayBookAutoSyncWorker.schedule(this)
    }
}
