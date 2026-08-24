package com.lerix.sdk.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lerix.sdk.LerixKeys

/**
 * Captures a notification tap before the app's own launcher Activity
 * starts — this is what lets `setOnNotificationTapped`/
 * `getInitialNotificationTap` work with zero required app-side Activity
 * code, mirroring the iOS SDK's automatic tap capture.
 */
internal class LerixNotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LerixKeys.bind(context.applicationContext)

        val data = mutableMapOf<String, String>()
        intent.extras?.keySet()?.forEach { key ->
            intent.extras?.getString(key)?.let { data[key] = it }
        }

        val payload = LerixNotificationPayload.fromData(data)
        LerixNotifications.deliverTap(payload)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtras(intent.extras ?: android.os.Bundle())
        }
        launchIntent?.let { context.startActivity(it) }
    }
}
