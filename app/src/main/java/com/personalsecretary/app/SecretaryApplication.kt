package com.personalsecretary.app

import android.app.Application
import com.personalsecretary.app.work.DailyBriefingWorker

class SecretaryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DailyBriefingWorker.schedule(this)
    }
}
