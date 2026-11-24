package pt.andrecarmo.syncstonedge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * Põe um número no marcador do telemóvel, pronto a ligar. Nunca liga sozinho: quem carrega
 * no botão verde é o utilizador.
 *
 * O Android limita aberturas de ecrãs a partir de segundo plano. Para funcionar com o ecrã
 * ligado e a app fechada, a app precisa de "Mostrar sobre outras apps" (e, no Xiaomi,
 * "Mostrar janelas pop-up em segundo plano"). Como reforço publica sempre uma notificação
 * com prioridade alta e ecrã completo, que abre o marcador ao tocar (ou com o ecrã bloqueado).
 */
object Dialer {

    private const val TAG = "Dialer"
    const val NOTIF_ID = 77

    fun clean(number: String): String = number.filter { it.isDigit() || it == '+' }

    /** Devolve "dialer" se abriu o marcador diretamente, "notification" se ficou só o aviso. */
    fun show(ctx: Context, number: String, name: String): String {
        val n = clean(number)
        val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        notify(ctx, n, name, dial)
        val canOverlay = Settings.canDrawOverlays(ctx)
        return try {
            ctx.startActivity(dial)
            if (canOverlay || MainActivity.visible) "dialer" else "notification"
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir o marcador", e)
            "notification"
        }
    }

    private fun notify(ctx: Context, number: String, name: String, dial: Intent) {
        val pi = PendingIntent.getActivity(
            ctx, 7, dial, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (name.isNotBlank()) "Ligar: $name" else "Ligar"
        val notif = Notification.Builder(ctx, SyncApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText("$number · toca para abrir o marcador")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Abrir marcador", pi).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif) }
    }

    fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}
