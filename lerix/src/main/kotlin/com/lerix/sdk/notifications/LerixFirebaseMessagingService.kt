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
import com.lerix.sdk.LerixKeys
import java.net.URL

class LerixFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        LerixKeys.bind(applicationContext)
        LerixNotifications.setDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        LerixKeys.bind(applicationContext)

        val data = message.data.toMutableMap()
        message.notification?.let { notification ->
            data.putIfAbsent("title", notification.title ?: "")
            data.putIfAbsent("body", notification.body ?: "")
            notification.imageUrl?.let { data.putIfAbsent("imageUrl", it.toString()) }
        }

        val payload = LerixNotificationPayload.fromData(data)
        LerixNotifications.deliverReceived(payload)
        showSystemNotification(payload, data)
    }

    private fun showSystemNotification(payload: LerixNotificationPayload, rawData: Map<String, String>) {
        val context = applicationContext
        val channelId = channelIdFor(rawData["sound"])
        ensureChannel(context, channelId, rawData["sound"])

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(context.applicationInfo.icon)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && rawData["sound"] != null) {
            builder.setSound(soundUri(context, rawData["sound"]))
        }

        payload.imageUrl?.let { url ->
            downloadBitmap(url)?.let { bitmap ->
                builder.setLargeIcon(bitmap)
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
            }
        }

        builder.setContentIntent(tapPendingIntent(context, rawData))

        val notificationId = payload.notificationId?.hashCode() ?: System.currentTimeMillis().toInt()
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
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

    private fun channelIdFor(sound: String?): String =
        if (sound.isNullOrEmpty()) "lerix_default" else "lerix_sound_${sound.substringBeforeLast('.')}"

    private fun ensureChannel(context: Context, channelId: String, sound: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(channelId, "Notifications", NotificationManager.IMPORTANCE_HIGH)
        if (!sound.isNullOrEmpty()) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()
            channel.setSound(soundUri(context, sound), audioAttributes)
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
