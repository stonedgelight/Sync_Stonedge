package pt.andrecarmo.syncstonedge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val onChange: () -> Unit = { refresh() }

    private lateinit var tvListener: TextView
    private lateinit var btnListener: Button
    private lateinit var tvBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnNotifPerm: Button
    private lateinit var tvServer: TextView
    private lateinit var tvPcs: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvFingerprint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        body(root, "Sync_Stonedge", 24f).setTypeface(null, Typeface.BOLD)
        body(root, "Notificações do telemóvel no PC · v${BuildConfig.VERSION_NAME}", 13f).setTextColor(MUTED)

        sectionTitle(root, "1 · Permissões")
        tvListener = body(root)
        btnListener = action(root, "Conceder acesso às notificações") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        tvBattery = body(root)
        btnBattery = action(root, "Retirar restrições de bateria") { requestBattery() }
        action(root, "Arranque automático (Xiaomi)") { openAutostart() }
        btnNotifPerm = action(root, "Permitir notificações desta app") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        sectionTitle(root, "2 · Ligação")
        tvServer = body(root)
        tvPcs = body(root)

        sectionTitle(root, "3 · Emparelhar um PC")
        body(root, "No PC, abre o Sync_Stonedge e escreve este código quando for pedido.", 13f).setTextColor(MUTED)
        tvPin = body(root, "", 34f).apply {
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        action(root, "Gerar código de emparelhamento") {
            SyncCore.newPin()
            refresh()
        }
        action(root, "Esquecer todos os PCs") { confirmForget() }
        tvFingerprint = body(root, "", 12f).apply { setTextColor(MUTED) }

        sectionTitle(root, "4 · Apps")
        action(root, "Escolher que apps enviam notificações") {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        sectionTitle(root, "5 · Teste")
        action(root, "Enviar notificação de teste") { postTest() }

        SyncCore.ensureRunning(this)
    }

    override fun onResume() {
        super.onResume()
        SyncCore.listeners.add(onChange)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        SyncCore.listeners.remove(onChange)
        handler.removeCallbacks(refresher)
    }

    private fun refresh() {
        val listenerOk = SyncListenerService.isEnabled(this)
        tvListener.text = if (listenerOk) "✔ Acesso às notificações concedido" else "✘ Sem acesso às notificações"
        btnListener.show(!listenerOk)

        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        tvBattery.text = if (batteryOk) "✔ Bateria sem restrições" else "✘ Bateria com otimização (o sistema pode parar a app)"
        btnBattery.show(!batteryOk)

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        btnNotifPerm.show(!notifOk)

        val srv = SyncCore.server
        val ips = SyncCore.localIps()
        tvServer.text = if (srv != null) {
            "✔ Servidor ativo na porta ${SyncCore.PORT}\n" + (if (ips.isEmpty()) "Sem rede" else ips.joinToString("\n"))
        } else {
            "✘ Servidor parado (a tentar arrancar)"
        }
        val names = srv?.authedNames().orEmpty()
        tvPcs.text = "PCs emparelhados: ${Prefs.clientCount(this)}\nLigados agora: " +
            (if (names.isEmpty()) "nenhum" else names.joinToString(", "))

        val pin = SyncCore.pairingPin
        val left = (SyncCore.pinExpiry - System.currentTimeMillis()) / 1000
        tvPin.text = if (pin != null && left > 0) {
            "${pin.substring(0, 3)} ${pin.substring(3)}\n" + "válido mais ${left / 60}:${"%02d".format(left % 60)}"
        } else {
            "— — —"
        }
        tvPin.textSize = if (pin != null && left > 0) 34f else 20f

        val fp = CertManager.fingerprint
        tvFingerprint.text = if (fp.isNotEmpty()) "Certificado do telemóvel: " + fp.take(16).chunked(4).joinToString(" ") else ""
    }

    private fun requestBattery() {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun openAutostart() {
        val intents = listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("Esquecer todos os PCs?")
            .setMessage("Os PCs deixam de receber notificações até voltarem a emparelhar com um novo código.")
            .setPositiveButton("Esquecer") { _, _ ->
                Prefs.clearClients(this)
                SyncCore.server?.disconnectAll()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun postTest() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        val hour = SimpleDateFormat("HH:mm:ss", Locale("pt", "PT")).format(Date())
        val n = Notification.Builder(this, SyncApp.CHANNEL_TEST)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Teste Sync_Stonedge")
            .setContentText("Se estás a ler isto no PC, a ligação funciona ($hour).")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + (System.currentTimeMillis() % 1000).toInt(), n)
    }
}
