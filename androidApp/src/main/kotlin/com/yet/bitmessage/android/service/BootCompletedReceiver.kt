package com.yet.bitmessage.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yet.bitmessage.android.di.appGraph

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.appGraph.meshServicePreferences.isAutoStartEnabled(true)) {
            MeshForegroundService.start(context.applicationContext)
        }
    }
}
