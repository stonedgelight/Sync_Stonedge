package pt.andrecarmo.syncstonedge

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Converte uma StatusBarNotification no JSON enviado ao PC, aplicando filtros:
 * - ignora notificações permanentes (música, serviços em primeiro plano) e resumos de grupo;
 * - ignora atualizações repetidas com o mesmo conteúdo (o WhatsApp republica muitas vezes);
 * - respeita o modo escolhido para a app (Enviar / Só o nome / Bloquear).
 */
object NotifMapper {

    private val lastSent = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 500
    }

    fun map(ctx: Context, sbn: StatusBarNotification, replay: Boolean): JSONObject? {
        val pkg = sbn.packageName ?: return null
        val n = sbn.notification ?: return null

        // As notificações da própria app só passam se forem de teste.
        if (pkg == ctx.packageName && n.channelId != SyncApp.CHANNEL_TEST) return null

        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null

        val mode = Prefs.mode(ctx, pkg)
        if (mode == Prefs.Mode.BLOCK) return null

        val ex: Bundle = n.extras ?: Bundle()
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        // MessagingStyle (WhatsApp, Telegram, Messages...): usa a última mensagem.
        lastMessage(ex)?.let { (sender, body) ->
            val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            if (!conversation.isNullOrBlank()) {
                title = conversation
                text = if (!sender.isNullOrBlank()) "$sender: $body" else body
            } else {
                if (!sender.isNullOrBlank()) title = sender
                text = body
            }
        }

        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null

        if (!replay) {
            val hash = "${title}\u0000${text}".hashCode()
            synchronized(lastSent) {
                if (lastSent[sbn.key] == hash) return null
                lastSent[sbn.key] = hash
            }
        }

        val appName = appLabel(ctx, pkg)
        val j = JSONObject()
            .put("t", "notif")
            .put("id", sbn.key)
            .put("pkg", pkg)
            .put("app", appName)
            .put("time", sbn.postTime)
            .put("replay", replay)
            .put("category", n.category ?: "")

        if (mode == Prefs.Mode.HIDE) {
            j.put("title", appName).put("text", "Nova notificação").put("hidden", true)
        } else {
            j.put("title", title ?: appName).put("text", text ?: "")
            if (!sub.isNullOrBlank()) j.put("sub", sub)
            IconCache.largeIcon(ctx, n)?.let { j.put("image", it) }
        }
        return j
    }

    fun forget(key: String) {
        synchronized(lastSent) { lastSent.remove(key) }
    }

    private fun lastMessage(ex: Bundle): Pair<String?, String>? {
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = arr.lastOrNull() as? Bundle ?: return null
        val body = last.getCharSequence("text")?.toString() ?: return null
        var sender = last.getCharSequence("sender")?.toString()
        if (sender == null && Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val p = last.getParcelable<Person>("sender_person")
            sender = p?.name?.toString()
        }
        return sender to body
    }

    private val labels = HashMap<String, String>()

    @Synchronized
    fun appLabel(ctx: Context, pkg: String): String {
        labels[pkg]?.let { return it }
        val pm = ctx.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        labels[pkg] = label
        return label
    }
}
