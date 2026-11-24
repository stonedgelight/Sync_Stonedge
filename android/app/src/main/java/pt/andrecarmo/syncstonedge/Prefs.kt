package pt.andrecarmo.syncstonedge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/** Preferências persistentes: modo de cada app e PCs emparelhados. */
object Prefs {

    /** O que fazer com as notificações de uma app. */
    enum class Mode(val label: String) {
        SEND("Enviar"),
        HIDE("Só o nome"),
        BLOCK("Bloquear");

        fun next(): Mode = when (this) {
            SEND -> HIDE
            HIDE -> BLOCK
            BLOCK -> SEND
        }
    }

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("sync_stonedge", Context.MODE_PRIVATE)

    // ---- Modos por app -------------------------------------------------------

    fun mode(ctx: Context, pkg: String): Mode {
        val raw = sp(ctx).getString("mode_$pkg", null) ?: return defaultMode(ctx)
        return runCatching { Mode.valueOf(raw) }.getOrDefault(defaultMode(ctx))
    }

    fun setMode(ctx: Context, pkg: String, mode: Mode) {
        sp(ctx).edit().putString("mode_$pkg", mode.name).apply()
    }

    /** Modo aplicado a apps sem escolha (incluindo apps instaladas no futuro). */
    fun defaultMode(ctx: Context): Mode =
        runCatching { Mode.valueOf(sp(ctx).getString("default_mode", Mode.SEND.name)!!) }.getOrDefault(Mode.SEND)

    fun setDefaultMode(ctx: Context, mode: Mode) {
        sp(ctx).edit().putString("default_mode", mode.name).apply()
    }

    // ---- Apps que já publicaram notificações --------------------------------

    @Volatile private var seenCache: MutableSet<String>? = null

    @Synchronized
    fun markSeen(ctx: Context, pkg: String) {
        val set = seenCache ?: HashSet(sp(ctx).getStringSet("seen_pkgs", emptySet()) ?: emptySet()).also { seenCache = it }
        if (set.add(pkg)) sp(ctx).edit().putStringSet("seen_pkgs", HashSet(set)).apply()
    }

    @Synchronized
    fun seen(ctx: Context): Set<String> =
        HashSet(seenCache ?: sp(ctx).getStringSet("seen_pkgs", emptySet()) ?: emptySet())

    // ---- PCs emparelhados (guardamos só o hash do token) ---------------------

    private fun clients(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("clients", "{}")!!) }.getOrDefault(JSONObject())

    @Synchronized
    fun addClient(ctx: Context, token: String, name: String) {
        val c = clients(ctx)
        c.put(sha256Hex(token.toByteArray()), name)
        sp(ctx).edit().putString("clients", c.toString()).apply()
    }

    fun clientName(ctx: Context, token: String): String? {
        if (token.isBlank()) return null
        val c = clients(ctx)
        val h = sha256Hex(token.toByteArray())
        return if (c.has(h)) c.optString(h) else null
    }

    fun clientCount(ctx: Context): Int = clients(ctx).length()

    @Synchronized
    fun clearClients(ctx: Context) {
        sp(ctx).edit().putString("clients", "{}").apply()
    }
}

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
