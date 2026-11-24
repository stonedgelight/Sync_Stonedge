package pt.andrecarmo.syncstonedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca o serviço quando o telemóvel liga ou a app é atualizada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncCore.ensureRunning(context)
    }
}
