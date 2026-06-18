package com.voicenotemd.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one swipe + one tap from anywhere to the capture screen (which is
 * the home destination, ADR 0001). For a capture-first app, time-to-mic beats any in-app
 * polish — this is the cheapest global entry point Android offers.
 *
 * Privacy-neutral: the tile only launches the existing MainActivity; no logic, no data.
 * The biometric gate (ADR 0013) still applies on launch when enabled.
 */
class CaptureTileService : TileService() {
    override fun onClick() {
        val launch =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: the Intent overload throws UnsupportedOperationException.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }
}
