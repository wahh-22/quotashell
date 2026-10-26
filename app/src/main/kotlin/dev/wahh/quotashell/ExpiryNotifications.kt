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
    var optedIn: Boolean
        get() = prefs.getBoolean("optIn", false)
        set(value) {
            prefs.edit().putBoolean("optIn", value).apply()
            if (!value) ProviderId.entries.forEach { manager.cancel(it.ordinal + 1) }
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
        val allowed =
            optedIn && manager.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < 33 ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                )
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
}
