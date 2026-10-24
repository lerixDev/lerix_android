@file:OptIn(LerixModuleApi::class)

package com.lerix.sdk.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lerix.sdk.module.LerixModuleApi
import com.lerix.sdk.module.LerixModuleHost
import java.net.URL

class LerixFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        LerixModuleHost.bind(applicationContext)
        LerixNotifications.setDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        LerixModuleHost.bind(applicationContext)

        when (val action = LerixPushAction.from(message.data)) {
            is LerixPushAction.Remove -> return cancelNotification(action.notificationId)
            LerixPushAction.ConfigRefresh -> return LerixModuleHost.onConfigRefreshPush()
            is LerixPushAction.Ignore -> return
            LerixPushAction.Display -> Unit
        }

        val data = message.data.toMutableMap()
        message.notification?.let { notification ->
            data.putIfAbsent("title", notification.title ?: "")
            data.putIfAbsent("body", notification.body ?: "")
            notification.imageUrl?.let { data.putIfAbsent("imageUrl", it.toString()) }
            // The backend sends the sound on the notification, not in data.
            notification.sound?.let { data.putIfAbsent("sound", it) }
        }

        val payload = LerixNotificationPayload.fromData(data)
        LerixNotifications.deliverReceived(payload)
        showSystemNotification(payload, data)
    }

    private fun showSystemNotification(payload: LerixNotificationPayload, rawData: Map<String, String>) {
        val context = applicationContext
        val sound = LerixNotificationChannel.customSound(rawData["sound"])
        val channelId = LerixNotificationChannel.idFor(sound)
        ensureChannel(context, channelId, sound)

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(context.applicationInfo.icon)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && sound != null) {
            builder.setSound(soundUri(context, sound))
        }

        payload.imageUrl?.let { url ->
            downloadBitmap(url)?.let { bitmap ->
                builder.setLargeIcon(bitmap)
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
            }
        }

        builder.setContentIntent(tapPendingIntent(context, rawData))

        // Tagged with the backend's notificationId (as the backend tags the
        // tray copy FCM shows in the background) so an unsend can cancel it.
        val tag = payload.notificationId
        val notificationId = tag?.hashCode() ?: System.currentTimeMillis().toInt()
        try {
            NotificationManagerCompat.from(context).notify(tag, notificationId, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked (Android 13+): nothing to show.
        }
    }

    /**
     * Cancels every way [notificationId] could be on screen: shown by this
     * service in the foreground (tag + hashCode id), shown by FCM in the tray
     * while backgrounded (tag + id 0), or shown by an older SDK (no tag).
     */
    private fun cancelNotification(notificationId: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.cancel(notificationId, notificationId.hashCode())
        manager.cancel(notificationId, 0)
        manager.cancel(notificationId.hashCode())
    }

    private fun tapPendingIntent(context: Context, rawData: Map<String, String>): PendingIntent {
        val intent = Intent(context, LerixNotificationTapReceiver::class.java).apply {
            val extras = Bundle()
            rawData.forEach { (key, value) -> extras.putString(key, value) }
            putExtras(extras)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), intent, flags)
    }

    private fun ensureChannel(context: Context, channelId: String, sound: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(channelId, "Notifications", NotificationManager.IMPORTANCE_HIGH)
        // A missing res/raw sound keeps the system default rather than
        // setSound(null), which would make the channel silent for good.
        soundUri(context, sound)?.let { uri ->
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()
            channel.setSound(uri, audioAttributes)
        }
        manager.createNotificationChannel(channel)
    }

    private fun soundUri(context: Context, sound: String?): Uri? {
        if (sound.isNullOrEmpty()) return null
        val resourceName = sound.substringBeforeLast('.')
        val resId = context.resources.getIdentifier(resourceName, "raw", context.packageName)
        if (resId == 0) return null
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    private fun downloadBitmap(url: String): Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
