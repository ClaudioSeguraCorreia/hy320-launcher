package dev.hy320.launcher

import android.graphics.Outline
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.hy320.launcher.glass.Glass
import java.util.Locale

sealed class HomeItem {
    abstract val id: String

    /** Barra superior: hora, data e botões do sistema. */
    data class Top(val shortcuts: List<AppEntry>) : HomeItem() {
        override val id get() = "top"
    }

    /**
     * Prateleira de vidro com uma linha de apps (Favoritos, Recentes).
     * [emptyHint] aparece quando não há apps; null = prateleira some vazia.
     */
    data class Shelf(
        override val id: String,
        val title: String,
        val entries: List<AppEntry>,
        val emptyHint: String? = null,
    ) : HomeItem()

    data class Header(val title: String) : HomeItem() {
        override val id get() = "header:$title"
    }

    data class Tile(val entry: AppEntry) : HomeItem() {
        override val id get() = entry.key
    }
}

/**
 * Tamanho dos blocos de app: colunas da grade e medidas de cada bloco (dp/sp).
 * A arte mantém a proporção dos banners de TV (~16:9).
 */
enum class TileSize(
    val columns: Int,
    val marginDp: Int,
    val paddingDp: Int,
    val artDp: Int,
    val iconPadDp: Int,
    val dockDp: Int,
    val textSp: Float,
) {
    LARGE(5, 9, 7, 84, 12, 176, 13f),
    MEDIUM(6, 8, 6, 69, 10, 148, 12f),
    SMALL(7, 7, 5, 58, 8, 126, 11f),
    MINIMUM(8, 6, 4, 52, 7, 110, 10f),
}

/**
 * Página inicial em uma única lista vertical. Cada view focável guarda seu
 * [AppEntry] na tag, o que permite achar o item focado e devolver o foco.
 */
class HomeAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onOptions: (AppEntry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<HomeItem> = emptyList()
        private set

    /** Ao mudar, os blocos precisam ser recriados (reatribuir o adapter à lista). */
    var tileSize = TileSize.MEDIUM

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<HomeItem>) {
        items = list
        notifyDataSetChanged()
    }

    /** Posição na lista principal (apps de prateleira ficam dentro dela). */
    fun positionOf(key: String) = items.indexOfFirst {
        it.id == key || (it is HomeItem.Shelf && it.entries.any { e -> e.key == key }) ||
            (it is HomeItem.Top && it.shortcuts.any { s -> s.key == key })
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HomeItem.Top -> TYPE_TOP
        is HomeItem.Shelf -> TYPE_SHELF
        is HomeItem.Header -> TYPE_HEADER
        is HomeItem.Tile -> TYPE_TILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TOP -> TopHolder(inflater.inflate(R.layout.item_top, parent, false))
            TYPE_SHELF -> ShelfHolder(inflater.inflate(R.layout.item_dock, parent, false))
            TYPE_HEADER -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_header, parent, false)) {}
            else -> TileHolder(inflateTile(parent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Top -> (holder as TopHolder).bind(item.shortcuts)
            is HomeItem.Shelf -> (holder as ShelfHolder).bind(item)
            is HomeItem.Header -> (holder.itemView as TextView).text = item.title
            is HomeItem.Tile -> (holder as TileHolder).bind(item.entry)
        }
    }

    // ---------- Barra superior ----------

    inner class TopHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val buttons: LinearLayout = view.findViewById(R.id.buttons)
        private val hint: TextView = view.findViewById(R.id.hint)

        init {
            // Data no idioma do sistema (ex.: "sexta-feira, 23 de outubro")
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
            view.findViewById<TextClock>(R.id.date).apply {
                format12Hour = pattern
                format24Hour = pattern
            }
        }

        fun bind(shortcuts: List<AppEntry>) {
            buttons.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)
            for (entry in shortcuts) {
                val button = inflater.inflate(R.layout.item_round_button, buttons, false)
                button.findViewById<ImageView>(R.id.icon).setImageDrawable(entry.image)
                button.tag = entry
                button.contentDescription = entry.label
                button.setOnClickListener { onOpen(entry) }
                button.setOnFocusChangeListener { _, hasFocus ->
                    // GlassTile cuida da animação; aqui só o nome do botão
                    hint.text = if (hasFocus) entry.label else ""
                }
                buttons.addView(button)
            }
        }
    }

    // ---------- Prateleiras (Favoritos, Recentes) ----------

    inner class ShelfHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.shelfTitle)
        private val list: RecyclerView = view.findViewById(R.id.dockList)
        private val empty: TextView = view.findViewById(R.id.empty)
        private val dockAdapter = DockAdapter()

        init {
            list.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = dockAdapter
            list.itemAnimator = null
            // Ao parar de rolar a doca, realinha o desfoque dos blocos
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) Glass.invalidateTree(recyclerView)
                }
            })
        }

        fun bind(shelf: HomeItem.Shelf) {
            title.text = shelf.title
            empty.text = shelf.emptyHint
            dockAdapter.entries = shelf.entries
            dockAdapter.notifyDataSetChanged()
            val isEmpty = shelf.entries.isEmpty()
            list.visibility = if (isEmpty) View.GONE else View.VISIBLE
            empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
    }

    private inner class DockAdapter : RecyclerView.Adapter<TileHolder>() {
        var entries: List<AppEntry> = emptyList()

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
            val view = inflateTile(parent)
            view.layoutParams.width = (tileSize.dockDp * parent.resources.displayMetrics.density).toInt()
            return TileHolder(view)
        }

        override fun onBindViewHolder(holder: TileHolder, position: Int) = holder.bind(entries[position])
    }

    // ---------- Bloco de app ----------

    /** Bloco com as medidas do tamanho escolhido. */
    private fun inflateTile(parent: ViewGroup): View {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        val density = parent.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        (view.layoutParams as ViewGroup.MarginLayoutParams).setMargins(
            px(tileSize.marginDp), px(tileSize.marginDp), px(tileSize.marginDp), px(tileSize.marginDp))
        view.setPadding(px(tileSize.paddingDp), px(tileSize.paddingDp), px(tileSize.paddingDp), px(tileSize.paddingDp))
        view.findViewById<View>(R.id.iconFrame).layoutParams.height = px(tileSize.artDp)
        view.findViewById<TextView>(R.id.label).textSize = tileSize.textSp
        return view
    }

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.iconFrame)
        private val image: ImageView = view.findViewById(R.id.image)
        private val gloss: View = view.findViewById(R.id.gloss)
        private val label: TextView = view.findViewById(R.id.label)
        private val density = view.resources.displayMetrics.density

        init {
            // Cantos arredondados na arte do app (recorte feito pela GPU)
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 14 * density)
                }
            }
            frame.clipToOutline = true
            view.setOnClickListener { (view.tag as? AppEntry)?.let(onOpen) }
            view.setOnLongClickListener {
                (view.tag as? AppEntry)?.let(onOptions)
                true
            }
        }

        fun bind(entry: AppEntry) {
            itemView.tag = entry
            label.text = entry.label
            val drawable = entry.image
            image.setImageDrawable(drawable)
            // Banners (TV) ocupam o quadro todo; ícones quadrados ficam centralizados
            val isBanner = drawable != null && drawable.intrinsicHeight > 0 &&
                drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight >= 1.4f
            // O reflexo de vidro vai só sobre banners: em ícones formaria uma "caixa" clara
            gloss.visibility = if (isBanner) View.VISIBLE else View.GONE
            if (isBanner) {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setPadding(0, 0, 0, 0)
            } else {
                val pad = (tileSize.iconPadDp * density).toInt()
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.setPadding(pad, pad, pad, pad)
            }
            itemView.invalidate()
        }
    }

    companion object {
        const val TYPE_TOP = 0
        const val TYPE_SHELF = 1
        const val TYPE_HEADER = 2
        const val TYPE_TILE = 3
    }
}
