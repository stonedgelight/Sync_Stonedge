package pt.andrecarmo.syncstonedge

import android.content.Context
import android.os.Build
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Certificado TLS próprio do telemóvel (EC P-256, auto-assinado, 10 anos).
 * É criado na primeira execução e guardado no armazenamento privado da app.
 * O PC fixa a impressão digital SHA-256 no emparelhamento (TOFU + PIN).
 */
object CertManager {

    private const val FILE = "tls.p12"
    private const val ALIAS = "sync_stonedge"
    private val PASS = "sync-stonedge-local".toCharArray()

    @Volatile private var cached: SSLContext? = null
    @Volatile var fingerprint: String = ""
        private set

    @Synchronized
    fun sslContext(ctx: Context): SSLContext {
        cached?.let { return it }
        val ks = loadOrCreate(ctx)
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        fingerprint = sha256Hex(cert.encoded)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, null)
        cached = ssl
        return ssl
    }

    fun fingerprint(ctx: Context): String {
        if (fingerprint.isEmpty()) runCatching { sslContext(ctx) }
        return fingerprint
    }

    private fun loadOrCreate(ctx: Context): KeyStore {
        val f = File(ctx.filesDir, FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            try {
                f.inputStream().use { ks.load(it, PASS) }
                if (ks.containsAlias(ALIAS)) return ks
            } catch (_: Exception) {
                // ficheiro corrompido: gera um novo (os PCs terão de emparelhar outra vez)
            }
        }
        ks.load(null, null)
        create(ks)
        f.outputStream().use { ks.store(it, PASS) }
        return ks
    }

    private fun create(ks: KeyStore) {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val model = Build.MODEL.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.ifBlank { "Android" }
        val name = X500Name("CN=Sync_Stonedge $model")
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - day),
            Date(now + 3650L * day),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ks.setKeyEntry(ALIAS, kp.private, PASS, arrayOf(cert))
    }
}
