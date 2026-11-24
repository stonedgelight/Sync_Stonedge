package pt.andrecarmo.syncstonedge

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Recebe do sistema todas as notificações publicadas no telemóvel. */
class SyncListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        SyncCore.ensureRunning(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        // O HyperOS por vezes desliga o listener; pede ao sistema para voltar a ligar.
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, SyncListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Prefs.markSeen(this, sbn.packageName)
        val j = NotifMapper.map(this, sbn, replay = false) ?: return
        SyncCore.publishNotif(j)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotifMapper.forget(sbn.key)
        SyncCore.publishRaw(JSONObject().put("t", "removed").put("id", sbn.key).toString())
    }

    /** Notificações ativas neste momento (enviadas a um PC quando se liga). */
    fun snapshot(): List<JSONObject> = try {
        activeNotifications?.mapNotNull { NotifMapper.map(this, it, replay = true) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        @Volatile var instance: SyncListenerService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, SyncListenerService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
