package pt.andrecarmo.syncstonedge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SyncCore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Estado da ligação", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "Notificação permanente que mantém o serviço ativo"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TEST, "Testes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notificações de teste enviadas para o PC"
            }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_TEST = "test"
    }
}
