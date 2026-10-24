package dev.hy320.launcher

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.hy320.launcher.glass.Glass
import dev.hy320.launcher.glass.GlassTile

/**
 * Painel de vidro lateral com linhas navegáveis pelo controle:
 * - Ação: OK executa;
 * - Escolha: ← → trocam o valor na hora (com prévia ao vivo);
 * - Info: só texto.
 * A navegação é circular: ↑ na primeira linha vai para a última, e vice-versa.
 */
class GlassMenu(private val host: FrameLayout, private val onClosed: () -> Unit) {

    sealed class Row(val label: String) {
        class Action(label: String, val closeAfter: Boolean = true, val run: () -> Unit) : Row(label)
        class Choice(label: String, val values: List<String>, var index: Int, val onChange: (Int) -> Unit) : Row(label)
        class Info(label: String) : Row(label)
    }

    var isOpen = false
        private set

    fun show(title: String, rows: List<Row>, focusIndex: Int = 0) {
        val inflater = LayoutInflater.from(host.context)
        host.removeAllViews()
        val root = inflater.inflate(R.layout.glass_menu, host, false)
        root.findViewById<TextView>(R.id.menuTitle).text = title
        val container = root.findViewById<LinearLayout>(R.id.menuRows)
        // Linhas são camadas de GPU: ao rolar o menu, realinha o desfoque delas
        (container.parent as View).setOnScrollChangeListener { _, _, _, _, _ -> Glass.invalidateTree(container) }

        val views = rows.map { row ->
            val view = inflater.inflate(R.layout.glass_menu_row, container, false) as GlassTile
            view.focusScale = 1.04f
            val label = view.findViewById<TextView>(R.id.rowLabel)
            val value = view.findViewById<TextView>(R.id.rowValue)
            label.text = row.label
            when (row) {
                // Executa antes de fechar: a lista se remonta e só então o foco volta
                is Row.Action -> view.setOnClickListener {
                    row.run()
                    if (row.closeAfter) close()
                }
                is Row.Choice -> value.text = choiceText(row)
                is Row.Info -> {
                    view.isFocusable = false
                    view.alpha = 0.85f
                    label.textSize = 14f
                }
            }
            container.addView(view)
            view
        }

        host.addView(root)
        host.visibility = View.VISIBLE
        isOpen = true

        val panel = root.findViewById<View>(R.id.panel)
        if (Glass.animations > 0) {
            panel.translationX = 48 * host.resources.displayMetrics.density
            panel.alpha = 0f
            panel.animate().translationX(0f).alpha(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        val focusable = views.filterIndexed { i, _ -> rows[i] !is Row.Info }
        views.forEachIndexed { i, view ->
            val pos = focusable.indexOf(view)
            if (pos < 0) return@forEachIndexed
            val row = rows[i]
            val value = view.findViewById<TextView>(R.id.rowValue)
            view.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    // Nas pontas, dá a volta (repetição de tecla segurada não dá a volta)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val wrap = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) pos == 0 else pos == focusable.lastIndex
                        if (!wrap) return@setOnKeyListener false
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            (if (pos == 0) focusable.last() else focusable.first()).requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (row !is Row.Choice) return@setOnKeyListener false
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                            row.index = (row.index + delta).mod(row.values.size)
                            value.text = choiceText(row)
                            row.onChange(row.index)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        (views.getOrNull(focusIndex)?.takeIf { it.isFocusable } ?: focusable.firstOrNull())?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        host.visibility = View.GONE
        host.removeAllViews()
        onClosed()
    }

    private fun choiceText(row: Row.Choice) = "‹  ${row.values[row.index]}  ›"
}
