package com.appgrowstack.sessions.compose

import android.os.Build

/**
 * Whether the app runs on an emulator (the SDK's, Cuttlefish, Genymotion), read from `Build`. An
 * emulator draws on the host, so the time a frame holds the main thread there says nothing of a
 * device's: the recording only spaces frames out on it and never stops for them
 * (`AppSessions.replayStopsOnSlowFrames`).
 */
internal object AndroidEmulator {
    val current: Boolean by lazy {
        runCatching {
            isEmulator(
                fingerprint = Build.FINGERPRINT, hardware = Build.HARDWARE, model = Build.MODEL,
                manufacturer = Build.MANUFACTURER, product = Build.PRODUCT, brand = Build.BRAND, device = Build.DEVICE,
            )
        }.getOrDefault(false)
    }

    fun isEmulator(
        fingerprint: String?,
        hardware: String?,
        model: String?,
        manufacturer: String?,
        product: String?,
        brand: String?,
        device: String?,
    ): Boolean {
        val f = fingerprint.orEmpty()
        val h = hardware.orEmpty()
        val m = model.orEmpty()
        val p = product.orEmpty()
        return f.startsWith("generic") || f.startsWith("unknown") || f.contains("emulator") || f.contains("sdk_gphone") ||
            h == "goldfish" || h == "ranchu" || h.contains("vbox86") || h.contains("cutf_cvm") ||
            m.contains("google_sdk") || m.contains("Emulator") || m.contains("Android SDK built for") || m.contains("sdk_gphone") ||
            manufacturer.orEmpty().contains("Genymotion") ||
            p.contains("sdk_gphone") || p == "sdk" || p.startsWith("sdk_") || p == "google_sdk" || p.contains("vbox86p") ||
            p.contains("emulator") || p.contains("simulator") ||
            (brand.orEmpty().startsWith("generic") && device.orEmpty().startsWith("generic"))
    }
}
