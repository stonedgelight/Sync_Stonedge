package pt.andrecarmo.syncstonedge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Serviço em primeiro plano que mantém o processo vivo (o HyperOS mata processos em
 * segundo plano de forma agressiva) e o servidor WSS a correr.
 */
class ServerService : Service() {

    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        goForeground()
        SyncCore.startServer()
        registerNsd()
        CallMonitor.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        SyncCore.startServer()
        CallMonitor.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        unregisterNsd()
        CallMonitor.stop()
        releaseWifiLock()
        SyncCore.stopServer()
        super.onDestroy()
    }

    private fun goForeground() {
        val n = buildStatus()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildStatus(): Notification {
        val names = SyncCore.server?.authedNames().orEmpty()
        val text = if (names.isEmpty()) "À espera do PC (porta ${SyncCore.PORT})"
        else "Ligado a: " + names.joinToString(", ")
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, SyncApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Sync_Stonedge")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun updateStatus() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatus()) }
        updateWifiLock()
    }

    /** Mantém o Wi-Fi acordado só enquanto há um PC ligado (poupa bateria no resto do tempo). */
    private fun updateWifiLock() {
        val connected = SyncCore.server?.authedNames()?.isNotEmpty() == true
        if (connected && wifiLock == null) {
            runCatching {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sync_stonedge").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else if (!connected) {
            releaseWifiLock()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "Sync_Stonedge-" + Build.MODEL.filter { it.isLetterOrDigit() }
                serviceType = SERVICE_TYPE
                port = SyncCore.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registado: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS falhou: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            val m = getSystemService(NsdManager::class.java)
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            nsdListener = l
        } catch (e: Exception) {
            Log.w(TAG, "mDNS indisponível", e)
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        runCatching { nsd?.unregisterService(l) }
        nsdListener = null
    }

    companion object {
        private const val TAG = "ServerService"
        private const val NOTIF_ID = 1
        const val SERVICE_TYPE = "_syncstonedge._tcp"

        @Volatile var running = false
            private set
        @Volatile var instance: ServerService? = null
            private set
    }
}
