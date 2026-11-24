package pt.andrecarmo.syncstonedge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Ponto central: arranque do servidor, emparelhamento e publicação de eventos. */
object SyncCore {

    const val PORT = 47800
    private const val TAG = "SyncCore"
    private const val PIN_VALIDITY_MS = 5 * 60 * 1000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    @Volatile var server: SyncServer? = null
        private set

    /** Callbacks da UI para atualizar o ecrã quando algo muda. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    // ---- Serviço e servidor ------------------------------------------------

    fun ensureRunning(ctx: Context) {
        val i = Intent(ctx, ServerService::class.java)
        try {
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível arrancar o serviço em primeiro plano", e)
        }
    }

    @Synchronized
    fun startServer() {
        if (server != null) return
        try {
            val s = SyncServer(app, PORT)
            s.start()
            server = s
        } catch (e: Exception) {
            Log.e(TAG, "Falha a arrancar o servidor", e)
            scheduleRetry()
        }
    }

    @Synchronized
    fun stopServer() {
        val s = server ?: return
        server = null
        runCatching { s.stop(1000) }
    }

    fun serverFailed() {
        synchronized(this) { server = null }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        main.postDelayed({ if (ServerService.running) startServer() }, 10_000)
    }

    fun runOnMain(block: () -> Unit) {
        main.post(block)
    }

    fun publishNotif(j: JSONObject) {
        server?.broadcastNotif(j)
    }

    fun publishRaw(text: String) {
        server?.broadcastRaw(text)
    }

    fun onClientsChanged() {
        main.post {
            ServerService.instance?.updateStatus()
            listeners.forEach { runCatching { it() } }
        }
    }

    // ---- Emparelhamento -----------------------------------------------------

    enum class PinResult { OK, NO_PIN, EXPIRED, WRONG, LOCKED }

    @Volatile var pairingPin: String? = null
        private set
    @Volatile var pinExpiry = 0L
        private set
    private var pinAttempts = 0

    @Synchronized
    fun newPin(): String {
        val p = String.format("%06d", SecureRandom().nextInt(1_000_000))
        pairingPin = p
        pinExpiry = System.currentTimeMillis() + PIN_VALIDITY_MS
        pinAttempts = 0
        return p
    }

    @Synchronized
    fun cancelPin() {
        pairingPin = null
    }

    @Synchronized
    fun checkPin(pin: String): PinResult {
        val p = pairingPin ?: return PinResult.NO_PIN
        if (System.currentTimeMillis() > pinExpiry) {
            pairingPin = null
            return PinResult.EXPIRED
        }
        if (pinAttempts >= 5) {
            pairingPin = null
            return PinResult.LOCKED
        }
        if (MessageDigest.isEqual(p.toByteArray(), pin.toByteArray())) {
            pairingPin = null
            onClientsChanged()
            return PinResult.OK
        }
        pinAttempts++
        return PinResult.WRONG
    }

    // ---- Rede ---------------------------------------------------------------

    /** Endereços IPv4 locais, com o nome da interface (ex.: "ap0 10.12.44.1"). */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { "${ni.name} ${it.hostAddress}" }
            }
    } catch (_: Exception) {
        emptyList()
    }
}
