package com.lerix.sdk

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.lerix.sdk.models.LerixApp
import com.lerix.sdk.models.LerixDevice
import java.util.Locale
import java.util.TimeZone

/** Collects device/app metadata for error reports and registration calls. */
internal object LerixDeviceInfo {
    fun collectDevice(): LerixDevice = LerixDevice(
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
        arc = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        osName = "Android",
        osVersion = Build.VERSION.RELEASE ?: "unknown",
        timeZone = TimeZone.getDefault().id,
        countryCode = Locale.getDefault().country.ifEmpty { "unknown" },
    )

    fun collectApp(): LerixApp {
        val context = LerixKeys.appContext
        val packageName = context.packageName
        val packageManager = context.packageManager
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA),
                ).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                "unknown"
            }
            val versionCode = longVersionCode(info)
            LerixApp(
                name = appName.ifEmpty { "unknown" },
                packageName = packageName,
                version = info.versionName?.ifEmpty { "0.0.0" } ?: "0.0.0",
                buildNo = versionCode.toString(),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            LerixApp(name = "unknown", packageName = packageName, version = "0.0.0", buildNo = "0")
        }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @SuppressLint("HardwareIds")
    fun deviceId(): String = try {
        Settings.Secure.getString(LerixKeys.appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
