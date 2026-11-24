package pt.andrecarmo.syncstonedge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Informa o PC do estado das chamadas (a tocar, em curso, terminada), para o Motor comercial
 * saber quando a chamada acabou e quanto durou. Não lê números nem grava áudio.
 * Precisa da permissão "Telefone" (READ_PHONE_STATE).
 */
object CallMonitor {

    private const val TAG = "CallMonitor"
    private var callback: Any? = null
    private var tm: TelephonyManager? = null
    @Volatile var state: String = "idle"
        private set

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(ctx: Context) {
        if (callback != null || !hasPermission(ctx)) return
        val t = ctx.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(s: Int) = onState(ctx, s)
                }
                t.registerTelephonyCallback(ctx.mainExecutor, cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("API antiga")
                    override fun onCallStateChanged(s: Int, phoneNumber: String?) = onState(ctx, s)
                }
                @Suppress("DEPRECATION")
                t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                callback = l
            }
            tm = t
            Log.i(TAG, "A acompanhar o estado das chamadas")
        } catch (e: Exception) {
            Log.w(TAG, "Sem acesso ao estado das chamadas", e)
        }
    }

    @Synchronized
    fun stop() {
        val t = tm ?: return
        val cb = callback ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) {
                t.unregisterTelephonyCallback(cb)
            } else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                t.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        }
        callback = null
        tm = null
    }

    private fun onState(ctx: Context, s: Int) {
        val name = when (s) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        if (name == state) return
        state = name
        if (name == "offhook") Dialer.clear(ctx)
        SyncCore.publishRaw(
            JSONObject().put("t", "call_state").put("state", name).put("ts", System.currentTimeMillis()).toString()
        )
    }
}
