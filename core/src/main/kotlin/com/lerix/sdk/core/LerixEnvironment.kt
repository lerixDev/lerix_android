package com.lerix.sdk.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

internal object LerixEnvironment {
    private val VALID = Regex("^[a-z][a-z0-9_-]{1,31}$")

    /** Validates an explicit environment; null (or invalid) means auto-detect. */
    fun normalize(value: String?): String? {
        val v = value?.trim()?.lowercase() ?: return null
        return if (VALID.matches(v)) v else null
    }

    fun isValid(value: String): Boolean = VALID.matches(value)

    /** `dev` for a debuggable build or an emulator, otherwise `prod` (contract §0). */
    fun detect(context: Context): String =
        if (isDebuggable(context) || isEmulator()) "dev" else "prod"

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_gphone") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    }
}
