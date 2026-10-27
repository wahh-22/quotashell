package dev.wahh.quotashell

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.wahh.quotashell.core.*

class ExpiryNotifications(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("notification_policy_v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    // "optInChoice" records an explicit user choice; the legacy default-off "optIn" key is ignored.
    var optedIn: Boolean
        get() = NotificationOptIn.enabled(if (prefs.contains(CHOICE)) prefs.getBoolean(CHOICE, true) else null)
        set(value) {
            prefs.edit().putBoolean(CHOICE, value).apply()
            if (!value) ProviderId.entries.forEach { manager.cancel(it.ordinal + 1) }
        }

    val permissionGranted: Boolean
        get() =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True once per install: the caller should show the Android permission prompt now. */
    fun claimPermissionRequest(): Boolean {
        val ask =
            NotificationOptIn.shouldRequestPermission(
                optedIn,
                permissionGranted,
                prefs.getBoolean(PERMISSION_ASKED, false),
                Build.VERSION.SDK_INT,
            )
        if (ask) prefs.edit().putBoolean(PERMISSION_ASKED, true).apply()
        return ask
    }

    init {
        manager.createNotificationChannel(
            NotificationChannel("reconnect", "Credential reconnection", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
        manager.cancel(provider.ordinal + 1)
    }

    fun observe(snapshot: ProviderSnapshot) {
        val provider = snapshot.provider
        val notified = prefs.getBoolean(provider.name, false)
        if (!ExpiryPolicy.nextEpisode(snapshot.connection, notified)) {
            clear(provider)
        }
        val allowed = optedIn && manager.areNotificationsEnabled() && permissionGranted
        if (!ExpiryPolicy.shouldNotify(snapshot.connection, notified, allowed)) return
        val intent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, ConnectionsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        try {
            manager.notify(
                provider.ordinal + 1,
                Notification
                    .Builder(context, "reconnect")
                    .setSmallIcon(R.drawable.ic_terminal)
                    .setContentTitle("${provider.title} · reconnect required")
                    .setContentText("Credentials expired or rejected during refresh. Open Connections to replace them.")
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build(),
            )
            prefs.edit().putBoolean(provider.name, true).apply()
        } catch (_: SecurityException) {
            // App/widget warning is independent of notification permission.
        }
    }

    private companion object {
        const val CHOICE = "optInChoice"
        const val PERMISSION_ASKED = "permissionAsked"
    }
}
