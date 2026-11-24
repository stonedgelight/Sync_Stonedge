package pt.andrecarmo.syncstonedge

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Converte ícones em PNG base64 para viajarem no JSON. */
object IconCache {

    private val appIcons = HashMap<String, String>()

    @Synchronized
    fun appIcon(ctx: Context, pkg: String): String? {
        appIcons[pkg]?.let { return it }
        val d = try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            return null
        }
        val b64 = encode(render(d, 96)) ?: return null
        appIcons[pkg] = b64
        return b64
    }

    /** Imagem grande da notificação (ex.: foto do contacto no WhatsApp). */
    fun largeIcon(ctx: Context, n: Notification): String? {
        val icon = n.getLargeIcon() ?: return null
        val d = try {
            icon.loadDrawable(ctx)
        } catch (_: Exception) {
            null
        } ?: return null
        return encode(render(d, 128))
    }

    private fun render(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) {
            return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val copy = d.constantState?.newDrawable()?.mutate() ?: d
        copy.setBounds(0, 0, size, size)
        copy.draw(c)
        return bmp
    }

    private fun encode(b: Bitmap): String? = try {
        ByteArrayOutputStream().use { out ->
            b.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }
}
