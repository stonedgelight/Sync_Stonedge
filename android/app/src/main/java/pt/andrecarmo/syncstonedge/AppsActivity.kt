package pt.andrecarmo.syncstonedge

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Lista de todas as apps (incluindo as que forem instaladas no futuro, porque a lista é
 * calculada sempre que o ecrã abre). Cada app alterna entre Enviar / Só o nome / Bloquear.
 */
class AppsActivity : Activity() {

    private class Row(val pkg: String, val label: String, val icon: Drawable?, val seen: Boolean)

    private val all = ArrayList<Row>()
    private val shown = ArrayList<Row>()
    private lateinit var adapter: BaseAdapter
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val search = EditText(this).apply {
            hint = "Procurar app"
            setSingleLine()
        }
        root.addView(search)

        val defaultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        defaultRow.addView(TextView(this).apply {
            text = "Apps novas ou sem escolha:"
            setTextColor(BRAND)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnDefault = Button(this).apply { isAllCaps = false }
        fun paintDefault() {
            val m = Prefs.defaultMode(this)
            btnDefault.text = m.label
            btnDefault.setTextColor(colorFor(m))
        }
        btnDefault.setOnClickListener {
            Prefs.setDefaultMode(this, Prefs.defaultMode(this).next())
            paintDefault()
            adapter.notifyDataSetChanged()
        }
        paintDefault()
        defaultRow.addView(btnDefault)
        root.addView(defaultRow)

        status = TextView(this).apply {
            text = "A carregar apps…"
            setTextColor(MUTED)
            textSize = 12f
        }
        root.addView(status)

        val list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                bindRow(convertView, shown[position])
        }
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString().orEmpty())
            }
        })

        Thread { load() }.start()
    }

    private fun load() {
        val pm = packageManager
        val seen = Prefs.seen(this)
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        val rows = apps.asSequence()
            .filter { it.packageName != packageName }
            .filter { ai ->
                pm.getLaunchIntentForPackage(ai.packageName) != null ||
                    seen.contains(ai.packageName) ||
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { ai ->
                Row(
                    ai.packageName,
                    pm.getApplicationLabel(ai).toString(),
                    runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    seen.contains(ai.packageName)
                )
            }
            .sortedWith(compareBy<Row>({ !it.seen }, { it.label.lowercase() }))
            .toList()
        runOnUiThread {
            all.clear()
            all.addAll(rows)
            status.text = "${rows.size} apps · as que já enviaram notificações aparecem primeiro. " +
                "Toca no estado para alternar."
            filter("")
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        shown.clear()
        shown.addAll(if (query.isEmpty()) all else all.filter {
            it.label.lowercase().contains(query) || it.pkg.contains(query)
        })
        adapter.notifyDataSetChanged()
    }

    private class Holder(val icon: ImageView, val label: TextView, val pkg: TextView, val mode: Button)

    private fun bindRow(convertView: View?, row: Row): View {
        val view: LinearLayout
        val h: Holder
        if (convertView == null) {
            view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val icon = ImageView(this)
            view.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            val label = TextView(this).apply { textSize = 15f; setTextColor(BRAND) }
            val pkg = TextView(this).apply { textSize = 11f; setTextColor(MUTED) }
            texts.addView(label)
            texts.addView(pkg)
            view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val mode = Button(this).apply { isAllCaps = false; minWidth = dp(110) }
            view.addView(mode)
            h = Holder(icon, label, pkg, mode)
            view.tag = h
        } else {
            view = convertView as LinearLayout
            h = view.tag as Holder
        }
        h.icon.setImageDrawable(row.icon)
        h.label.text = row.label
        h.pkg.text = row.pkg
        val m = Prefs.mode(this, row.pkg)
        h.mode.text = m.label
        h.mode.setTextColor(colorFor(m))
        h.mode.setOnClickListener {
            Prefs.setMode(this, row.pkg, Prefs.mode(this, row.pkg).next())
            adapter.notifyDataSetChanged()
        }
        return view
    }

    private fun colorFor(m: Prefs.Mode): Int = when (m) {
        Prefs.Mode.SEND -> ACCENT
        Prefs.Mode.HIDE -> Color.parseColor("#C77700")
        Prefs.Mode.BLOCK -> Color.parseColor("#B3261E")
    }
}
