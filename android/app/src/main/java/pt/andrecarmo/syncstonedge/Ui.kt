package pt.andrecarmo.syncstonedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pequenos auxiliares para construir a interface sem XML. */
internal fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal val BRAND = Color.parseColor("#0E2A33")
internal val ACCENT = Color.parseColor("#1F9D7A")
internal val MUTED = Color.parseColor("#5A6B70")

internal fun Activity.sectionTitle(parent: LinearLayout, text: String): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ACCENT)
        isAllCaps = true
        setPadding(0, dp(20), 0, dp(6))
        parent.addView(this)
    }

internal fun Activity.body(parent: LinearLayout, text: String = "", size: Float = 15f): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(BRAND)
        setPadding(0, dp(4), 0, dp(4))
        parent.addView(this)
    }

internal fun Activity.action(parent: LinearLayout, text: String, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        parent.addView(
            this,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

internal fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
