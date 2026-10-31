// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 手机事件触发器：充电 / 低电量时执行主动任务。

package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.model.ScheduledTaskTrigger
import me.rerere.rikkahub.data.schedule.ScheduledTaskRunner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ScheduleTriggerReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ScheduledTaskTrigger.CHARGING
            Intent.ACTION_BATTERY_LOW -> ScheduledTaskTrigger.BATTERY_LOW
            else -> return
        }
        val appScope: AppScope by inject()
        val runner: ScheduledTaskRunner by inject()
        val pending = goAsync()
        appScope.launch {
            try {
                val count = runner.runForTrigger(trigger)
                Log.i("ScheduleTriggerReceiver", "trigger=$trigger ran=$count")
            } catch (e: Throwable) {
                Log.e("ScheduleTriggerReceiver", "trigger=$trigger failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
