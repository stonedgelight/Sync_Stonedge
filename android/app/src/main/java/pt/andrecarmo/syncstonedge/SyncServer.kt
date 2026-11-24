package pt.andrecarmo.syncstonedge

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.SecureRandom

/** Estado de cada PC ligado. */
class ClientState {
    @Volatile var authed = false
    @Volatile var name = ""
    val iconsSent: MutableSet<String> = HashSet()
}

/**
 * Servidor WebSocket seguro (WSS) a correr no telemóvel.
 * O PC é o cliente: assim não é preciso abrir portas na firewall do Windows nem do Ubuntu.
 *
 * Protocolo (JSON, um objeto por mensagem, campo "t" = tipo):
 *   PC -> tel: hello{token, client} | pair{pin, client} | ping | dial{req, number, name, lead_id}
 *   tel -> PC: welcome{device, android, app} | paired{token} | pair_failed{reason}
 *              unauthorized | notif{...} | removed{id} | pong
 *              dial_ack{req, ok, via, error} | call_state{state: ringing|offhook|idle, ts}
 */
class SyncServer(private val app: Context, port: Int) : WebSocketServer(InetSocketAddress(port)) {

    init {
        setReuseAddr(true)
        setConnectionLostTimeout(30)
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(CertManager.sslContext(app)))
    }

    override fun onStart() {
        Log.i(TAG, "Servidor ativo na porta $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val addr = conn.remoteSocketAddress?.address
        val local = addr != null && (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)
        if (!local) {
            conn.close(1008, "Apenas rede local")
            return
        }
        conn.setAttachment(ClientState())
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        SyncCore.onClientsChanged()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val st = conn.getAttachment<ClientState>() ?: return
        val msg = try {
            JSONObject(message)
        } catch (_: Exception) {
            return
        }
        when (msg.optString("t")) {
            "hello" -> {
                val token = msg.optString("token")
                val known = Prefs.clientName(app, token)
                if (known == null) {
                    conn.send(JSONObject().put("t", "unauthorized").toString())
                    conn.close(4001, "Token desconhecido")
                } else {
                    authorize(conn, st, msg.optString("client", known))
                }
            }

            "pair" -> {
                val client = msg.optString("client", "PC").take(60)
                when (val r = SyncCore.checkPin(msg.optString("pin").trim())) {
                    SyncCore.PinResult.OK -> {
                        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        val token = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                        Prefs.addClient(app, token, client)
                        conn.send(JSONObject().put("t", "paired").put("token", token).toString())
                        authorize(conn, st, client)
                    }

                    else -> conn.send(JSONObject().put("t", "pair_failed").put("reason", r.name).toString())
                }
            }

            "ping" -> conn.send("{\"t\":\"pong\"}")

            "dial" -> {
                if (!st.authed) return
                val req = msg.optString("req")
                val number = msg.optString("number")
                val name = msg.optString("name")
                SyncCore.runOnMain {
                    val reply = JSONObject().put("t", "dial_ack").put("req", req)
                    if (Dialer.clean(number).count { it.isDigit() } < 3) {
                        reply.put("ok", false).put("error", "Número inválido")
                    } else {
                        reply.put("ok", true).put("via", Dialer.show(app, number, name))
                    }
                    runCatching { conn.send(reply.toString()) }
                }
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.w(TAG, "Erro no servidor", ex)
        if (conn == null) SyncCore.serverFailed()
    }

    private fun authorize(conn: WebSocket, st: ClientState, name: String) {
        st.authed = true
        st.name = name
        conn.send(
            JSONObject()
                .put("t", "welcome")
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put("app", BuildConfig.VERSION_NAME)
                .toString()
        )
        SyncListenerService.instance?.snapshot()?.forEach { sendNotif(conn, st, it) }
        SyncCore.onClientsChanged()
    }

    fun broadcastNotif(n: JSONObject) {
        for (c in connections) {
            val st = c.getAttachment<ClientState>() ?: continue
            if (st.authed && c.isOpen) sendNotif(c, st, n)
        }
    }

    fun broadcastRaw(text: String) {
        for (c in connections) {
            val st = c.getAttachment<ClientState>() ?: continue
            if (st.authed && c.isOpen) runCatching { c.send(text) }
        }
    }

    private fun sendNotif(c: WebSocket, st: ClientState, n: JSONObject) {
        val out = JSONObject(n.toString())
        val pkg = n.optString("pkg")
        val firstTime = synchronized(st.iconsSent) { pkg.isNotEmpty() && st.iconsSent.add(pkg) }
        if (firstTime) IconCache.appIcon(app, pkg)?.let { out.put("icon", it) }
        runCatching { c.send(out.toString()) }
    }

    fun authedNames(): List<String> = connections.mapNotNull { c ->
        val st = c.getAttachment<ClientState>()
        if (st != null && st.authed && c.isOpen) st.name else null
    }

    fun disconnectAll() {
        for (c in connections) runCatching { c.close(4002, "Emparelhamento removido") }
    }

    companion object {
        private const val TAG = "SyncServer"
    }
}
