package dev.hy320.launcher

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.hy320.launcher.GlassMenu.Row
import dev.hy320.launcher.glass.BoxBlur
import dev.hy320.launcher.glass.Glass
import dev.hy320.launcher.glass.Wallpapers
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var adapter: HomeAdapter
    private lateinit var menu: GlassMenu
    private val loader = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry> = emptyList()
    private var shortcuts: List<AppEntry> = emptyList()
    private var firstLoad = true

    /** Papel de parede em tamanho de tela (reaproveitado ao mudar só o desfoque). */
    @Volatile private var wallpaperFull: Bitmap? = null

    /** Um app foi aberto: reorganiza os Recentes quando o launcher sair da tela. */
    private var recentsChanged = false

    /** Item que tinha foco antes de abrir um menu. */
    private var focusAfterMenu: String? = null
    private var menuBack: (() -> Unit)? = null
    private var menuTitle: String? = null

    // Recarrega a lista quando um app é instalado, removido ou atualizado
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    // Pendrive (ou o armazenamento interno, no boot) montado: busca imagens novas
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = importImages()
    }

    private val wallpaperRender = Runnable { loadWallpaper(render = true) }

    /** Tentativas restantes de ler a imagem do usuário enquanto o armazenamento monta. */
    @Volatile private var wallpaperRetries = WALLPAPER_RETRIES
    private val wallpaperRetry = Runnable { loadWallpaper(render = true, retrying = true) }

    /** Ao parar de rolar, realinha o desfoque do vidro com o fundo (uma vez). */
    private val realignGlassOnIdle = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) Glass.invalidateTree(grid)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        prefs.applyToGlass()
        root = findViewById(R.id.root)
        menu = GlassMenu(findViewById(R.id.overlay), ::onMenuClosed)

        adapter = HomeAdapter(::open, ::showAppOptions)
        adapter.tileSize = tileSizeFromPrefs()
        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, adapter.tileSize.columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.getItemViewType(position) == HomeAdapter.TYPE_TILE) 1 else spanCount
            }
        }
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.addOnScrollListener(realignGlassOnIdle)

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(mediaReceiver, IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") })
        loadWallpaper(render = true)
        reload()
        importImages()
    }

    // ---------- Recuperação do foco de janela ----------

    private var isResumedNow = false
    private var lastFocusRefreshAt = 0L

    /**
     * Na tela, mas sem foco de janela: todas as teclas seriam descartadas pelo
     * sistema. Acontece quando o Filtro do controle liga enquanto a janela abre.
     * Abre e fecha uma janela invisível para recuperar (no máximo 1x a cada 10 s).
     */
    private val focusCheck = Runnable {
        val now = SystemClock.uptimeMillis()
        if (isResumedNow && !hasWindowFocus() && now - lastFocusRefreshAt > 10_000) {
            lastFocusRefreshAt = now
            Log.w(TAG, "Launcher na tela sem foco de janela: recuperando")
            startActivity(Intent(this, FocusRefreshActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    private fun scheduleFocusCheck(delayMs: Long) {
        root.removeCallbacks(focusCheck)
        root.postDelayed(focusCheck, delayMs)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        KeyFilterService.onConnected = { scheduleFocusCheck(700) }
        scheduleFocusCheck(1500)
    }

    override fun onPause() {
        isResumedNow = false
        KeyFilterService.onConnected = null
        root.removeCallbacks(focusCheck)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) root.removeCallbacks(focusCheck)
    }

    /**
     * O launcher saiu da tela (um app abriu): atualiza os Recentes agora, sem o
     * usuário ver a lista se rearrumar. Ao voltar, o foco fica no app que foi aberto.
     */
    override fun onStop() {
        super.onStop()
        if (recentsChanged) {
            recentsChanged = false
            val lastOpened = prefs.recents.firstOrNull()
            rebuild()
            grid.scrollToPosition(0)
            lastOpened?.let { focusKey(it) }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(mediaReceiver)
        loader.shutdownNow()
        super.onDestroy()
    }

    /** Botão Home com o launcher já aberto: fecha menus e volta ao início. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) {
            focusAfterMenu = null
            menu.close()
            focusDefault()
        }
    }

    @Deprecated("Mantido para Android 11")
    override fun onBackPressed() {
        // Na tela inicial, "Voltar" só fecha menus
        if (menu.isOpen) menuBack?.invoke() ?: menu.close()
    }

    // ---------- Filtros do controle ----------

    /**
     * Descarta setas que chegaram atrasadas. Quando o sistema trava, as teclas
     * ficam numa fila e são entregues de uma vez; sem este filtro, o foco
     * "dispara" vários ícones. Uma tecla normal chega em poucos milissegundos.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isDpad(event.keyCode)) {
            val age = SystemClock.uptimeMillis() - event.eventTime
            if (age > STALE_KEY_MS) {
                Log.d(TAG, "Tecla atrasada descartada: ${KeyEvent.keyCodeToString(event.keyCode)} (${age} ms)")
                return true
            }
            if (event.repeatCount > 0 && !acceptRepeat(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Repetições automáticas do Android (tecla "segurada"). Quando o "soltou"
     * do controle chega atrasado, o sistema gera repetições falsas a cada 50 ms.
     * Só aceita repetição após segurar de verdade, e num ritmo controlado.
     */
    private var lastRepeatAt = 0L

    private fun acceptRepeat(event: KeyEvent): Boolean {
        val held = event.eventTime - event.downTime
        if (held < HOLD_BEFORE_REPEAT_MS) {
            Log.d(TAG, "Repetição falsa descartada: ${KeyEvent.keyCodeToString(event.keyCode)} (${held} ms segurada)")
            return false
        }
        if (event.eventTime - lastRepeatAt < REPEAT_INTERVAL_MS) return false
        lastRepeatAt = event.eventTime
        return true
    }

    /** Botão Menu do controle: opções do app focado (ou fecha o menu aberto). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menu.isOpen) menu.close() else focusedEntry()?.let(::showAppOptions)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Papel de parede e vidro ----------

    /**
     * Gera o fundo e sua versão desfocada (1/8 da tela) fora da thread de tela.
     * [render] = false reaproveita a imagem atual e só refaz o desfoque.
     */
    private fun loadWallpaper(render: Boolean, retrying: Boolean = false) {
        val id = prefs.background
        val theme = prefs.theme
        val radius = BLUR_RADIUS[prefs.blur.coerceIn(0, 2)]
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cached = if (render) null else wallpaperFull
        root.removeCallbacks(wallpaperRetry)
        if (!retrying) wallpaperRetries = WALLPAPER_RETRIES
        loader.execute {
            val image = cached ?: Wallpapers.render(id, w, h)
            // Imagem ainda ilegível (armazenamento montando no boot): mostra o fundo
            // padrão por enquanto e tenta de novo daqui a pouco
            if (image == null && wallpaperRetries > 0) {
                wallpaperRetries--
                root.postDelayed(wallpaperRetry, WALLPAPER_RETRY_MS)
                if (retrying) return@execute
            }
            val full = image ?: Wallpapers.render(theme, w, h)!!
            if (retrying && image != null) Log.i(TAG, "Imagem de fundo carregada após o armazenamento montar")
            val small = Bitmap.createScaledBitmap(full, max(1, w / 8), max(1, h / 8), true)
            val blurred = BoxBlur.blur(small, radius)
            small.recycle()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val previous = wallpaperFull
                wallpaperFull = full
                // Fundo da própria janela: evita pintar a tela inteira duas vezes por quadro
                window.setBackgroundDrawable(BitmapDrawable(resources, full).apply { isFilterBitmap = true })
                Glass.blurred = blurred
                Glass.screenWidth = if (root.width > 0) root.width else w
                Glass.screenHeight = if (root.height > 0) root.height else h
                Glass.changed(root)
                // Libera a imagem anterior (e sua textura na GPU) quando a tela já usa a nova.
                // Sem isso, cada troca de fundo deixava ~4–8 MB presos até o coletor passar.
                if (previous != null && previous !== full) {
                    root.postDelayed({ if (wallpaperFull !== previous) previous.recycle() }, 1000)
                }
            }
        }
    }

    /** Copia imagens novas do pendrive e avisa quantas chegaram. */
    private fun importImages() {
        loader.execute {
            val copied = WallpaperImport.run(applicationContext)
            if (copied == 0) return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(getString(R.string.images_imported, copied))
                if (menu.isOpen && menuTitle == getString(R.string.set_own_images)) showOwnImages()
            }
        }
    }

    /** Aplica ajustes visuais na hora (prévia ao vivo atrás do menu). */
    private fun applyStyle() {
        prefs.applyToGlass()
        Glass.changed(root)
        adapter.notifyDataSetChanged()
    }

    private fun tileSizeFromPrefs() = TileSize.values()[prefs.iconSize.coerceIn(0, TileSize.values().size - 1)]

    /** Novo tamanho de ícones: muda as colunas e recria os blocos com as novas medidas. */
    private fun applyTileSize() {
        val size = tileSizeFromPrefs()
        if (size == adapter.tileSize) return
        adapter.tileSize = size
        (grid.layoutManager as GridLayoutManager).spanCount = size.columns
        grid.adapter = adapter
        afterLayout { Glass.invalidateTree(grid) }
    }

    // ---------- Dados ----------

    private fun reload() {
        loader.execute {
            val loadedApps = AppRepository.load(applicationContext)
            val loadedShortcuts = AppRepository.shortcuts(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = loadedApps
                shortcuts = loadedShortcuts
                val focusedKey = focusedEntry()?.key
                rebuild()
                when {
                    firstLoad -> focusDefault()
                    focusedKey != null && !menu.isOpen -> focusKey(focusedKey)
                }
                firstLoad = false
            }
        }
    }

    /** Monta a página: barra superior, favoritos, recentes e grade de apps. */
    private fun rebuild() {
        val byKey = apps.associateBy { it.key }
        val hidden = prefs.hidden
        val favorites = prefs.favorites.mapNotNull { byKey[it] }
        val favoriteKeys = favorites.map { it.key }.toSet()
        val others = apps.filter { it.key !in favoriteKeys && it.key !in hidden }

        val items = mutableListOf<HomeItem>(HomeItem.Top(shortcuts))
        // Prateleira de favoritos desligada: os favoritos vêm primeiro na grade, na ordem escolhida
        val gridApps = if (prefs.showFavorites) {
            items += HomeItem.Shelf(SHELF_FAVORITES, getString(R.string.section_favorites), favorites,
                emptyHint = getString(R.string.dock_empty))
            others
        } else {
            favorites + others
        }
        if (prefs.showRecents) {
            val recents = prefs.recents.filter { it !in hidden }.mapNotNull { byKey[it] }
            if (recents.isNotEmpty()) {
                items += HomeItem.Shelf(SHELF_RECENTS, getString(R.string.section_recents), recents)
            }
        }
        if (gridApps.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.section_apps))
            gridApps.mapTo(items) { HomeItem.Tile(it) }
        }
        adapter.submit(items)
    }

    // ---------- Foco ----------

    private fun focusedEntry(): AppEntry? = currentFocus?.tag as? AppEntry

    /** Primeiro favorito; sem favoritos, o primeiro app da grade. */
    private fun focusDefault() {
        val key = adapter.items.firstNotNullOfOrNull {
            when (it) {
                is HomeItem.Shelf -> it.entries.firstOrNull()?.key
                is HomeItem.Tile -> it.entry.key
                else -> null
            }
        } ?: return
        grid.scrollToPosition(0)
        focusKey(key)
    }

    /** Dá foco ao item com esta chave, esperando a lista terminar de se montar. */
    private fun focusKey(key: String, retry: Boolean = true) {
        afterLayout {
            val view = findTile(grid, key)
            if (view != null) {
                view.requestFocus()
            } else if (retry) {
                val pos = adapter.positionOf(key)
                if (pos >= 0) {
                    grid.scrollToPosition(pos)
                    focusKey(key, retry = false)
                }
            }
        }
    }

    private fun findTile(view: View, key: String): View? {
        if ((view.tag as? AppEntry)?.key == key) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findTile(view.getChildAt(i), key)?.let { return it }
        }
        return null
    }

    private fun afterLayout(action: () -> Unit) {
        if (!grid.isLayoutRequested) {
            grid.post(action)
            return
        }
        grid.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                action()
            }
        })
    }

    // ---------- Ações ----------

    private fun open(entry: AppEntry) {
        if (entry.key == AppRepository.KEY_CUSTOMIZE) {
            showCustomize()
            return
        }
        val intent = entry.intent ?: return
        try {
            startActivity(intent)
            if (!entry.isShortcut) {
                prefs.recordLaunch(entry.key)
                recentsChanged = true
            }
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.open_failed, entry.label))
        } catch (e: SecurityException) {
            toast(getString(R.string.open_failed, entry.label))
        }
    }

    // ---------- Menus ----------

    private fun openMenu(title: String, rows: List<Row>, focusIndex: Int = 0, back: (() -> Unit)? = null) {
        if (!menu.isOpen) {
            focusAfterMenu = focusedEntry()?.key
            grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        menuBack = back
        menuTitle = title
        menu.show(title, rows, focusIndex)
    }

    private fun onMenuClosed() {
        menuBack = null
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusAfterMenu?.let { focusKey(it) } ?: focusDefault()
    }

    private fun showAppOptions(entry: AppEntry) {
        if (entry.isShortcut) return
        val pkg = entry.key
        val favorites = prefs.favorites
        val rows = mutableListOf<Row>()
        if (pkg in favorites) {
            rows += Row.Action(getString(R.string.opt_remove_favorite)) { prefs.toggleFavorite(pkg); rebuild() }
            if (favorites.first() != pkg) {
                rows += Row.Action(getString(R.string.opt_move_left)) { prefs.moveFavorite(pkg, -1); rebuild() }
            }
            if (favorites.last() != pkg) {
                rows += Row.Action(getString(R.string.opt_move_right)) { prefs.moveFavorite(pkg, +1); rebuild() }
            }
        } else {
            rows += Row.Action(getString(R.string.opt_add_favorite)) { prefs.toggleFavorite(pkg); rebuild() }
        }
        rows += Row.Action(getString(R.string.opt_hide)) {
            prefs.hide(pkg)
            focusAfterMenu = null
            rebuild()
            toast(getString(R.string.hidden_toast, entry.label))
        }
        rows += Row.Action(getString(R.string.opt_app_info)) {
            // isShortcut = true: abrir a tela de informações não conta como uso do app nos Recentes
            open(entry.copy(
                intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                isShortcut = true,
            ))
        }
        openMenu(entry.label, rows)
    }

    private fun showCustomize(focusIndex: Int = 0) {
        importImages()
        // Tema: cor do vidro com qualquer fundo; sem imagem própria, também o fundo
        val themes = Wallpapers.themes()
        val themeIndex = themes.indexOfFirst { it.id == prefs.theme }.coerceAtLeast(0)
        val levels = listOf(getString(R.string.level_low), getString(R.string.level_medium), getString(R.string.level_high))
        val rows = listOf(
            Row.Choice(getString(R.string.set_theme), themes.map { it.name }, themeIndex) {
                prefs.theme = themes[it].id
                applyStyle()
                if (!prefs.hasOwnImage) {
                    // Espera o usuário parar de trocar antes de gerar o fundo
                    root.removeCallbacks(wallpaperRender)
                    root.postDelayed(wallpaperRender, 250)
                }
            },
            Row.Choice(getString(R.string.set_transparency), (0..10).map { "${it * 10}%" }, prefs.transparency / 10) {
                prefs.transparency = it * 10
                applyStyle()
            },
            Row.Choice(getString(R.string.set_blur), levels, prefs.blur) {
                prefs.blur = it
                loadWallpaper(render = false)
            },
            Row.Choice(
                getString(R.string.set_reflections),
                listOf(getString(R.string.refl_off), getString(R.string.refl_soft), getString(R.string.refl_strong)),
                prefs.reflections,
            ) {
                prefs.reflections = it
                applyStyle()
            },
            Row.Choice(
                getString(R.string.set_animations),
                listOf(getString(R.string.anim_off), getString(R.string.anim_reduced), getString(R.string.anim_full)),
                prefs.animations,
            ) {
                prefs.animations = it
                applyStyle()
            },
            Row.Choice(
                getString(R.string.set_accent),
                Glass.accents.map { it.name },
                Glass.accents.indexOf(Glass.accent(prefs.accent)),
            ) {
                prefs.accent = Glass.accents[it].id
                applyStyle()
            },
            Row.Choice(
                getString(R.string.set_light_mode),
                listOf(getString(R.string.off), getString(R.string.on)),
                if (prefs.lightMode) 1 else 0,
            ) {
                prefs.lightMode = it == 1
                applyStyle()
            },
            Row.Choice(
                getString(R.string.set_icon_size),
                listOf(
                    getString(R.string.size_large), getString(R.string.size_medium),
                    getString(R.string.size_small), getString(R.string.size_minimum),
                ),
                prefs.iconSize,
            ) {
                prefs.iconSize = it
                applyTileSize()
            },
            Row.Choice(
                getString(R.string.set_show_favorites),
                listOf(getString(R.string.off), getString(R.string.on)),
                if (prefs.showFavorites) 1 else 0,
            ) {
                prefs.showFavorites = it == 1
                rebuild()
            },
            Row.Choice(
                getString(R.string.set_show_recents),
                listOf(getString(R.string.off), getString(R.string.on)),
                if (prefs.showRecents) 1 else 0,
            ) {
                prefs.showRecents = it == 1
                rebuild()
            },
            Row.Choice(
                getString(R.string.set_filter),
                listOf(getString(R.string.off), getString(R.string.on)),
                if (prefs.filterEnabled) 1 else 0,
            ) {
                prefs.filterEnabled = it == 1
                FilterGuard.sync(this)
            },
            Row.Action("${getString(R.string.set_hidden)} (${prefs.hidden.size})", closeAfter = false) { showHidden() },
            Row.Action(getString(R.string.set_own_images), closeAfter = false) { showOwnImages() },
            Row.Action(getString(R.string.set_about), closeAfter = false) { showAbout() },
        )
        openMenu(getString(R.string.customize), rows, focusIndex)
    }

    private fun showHidden() {
        val hiddenApps = apps.filter { it.key in prefs.hidden }
        val rows = mutableListOf<Row>()
        if (hiddenApps.isEmpty()) rows += Row.Info(getString(R.string.hidden_none))
        hiddenApps.mapTo(rows) { app ->
            Row.Action(getString(R.string.hidden_show, app.label), closeAfter = false) {
                prefs.hidden = prefs.hidden - app.key
                rebuild()
                showHidden()
            }
        }
        rows += Row.Action(getString(R.string.back), closeAfter = false) { showCustomize(INDEX_HIDDEN) }
        openMenu(getString(R.string.set_hidden), rows, back = { showCustomize(INDEX_HIDDEN) })
    }

    /**
     * Lista das imagens do usuário: OK aplica como fundo (✓ marca a atual).
     * A lista é lida na hora, então imagens novas aparecem sozinhas.
     */
    private fun showOwnImages() {
        // Pega imagens postas no pendrive depois de conectado (ao copiar, a lista se refaz)
        importImages()
        val granted = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val images = Wallpapers.userImages(this)
        val rows = mutableListOf<Row>()
        if (!granted) {
            rows += Row.Action(getString(R.string.images_permission), closeAfter = false) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_IMAGES)
            }
        }
        val current = prefs.wallpaper
        // Sem imagem: volta ao fundo de cores do tema
        val noneMark = if (!prefs.hasOwnImage) "✓  " else ""
        rows += Row.Action("$noneMark${getString(R.string.images_off)}", closeAfter = false) {
            prefs.wallpaper = ""
            loadWallpaper(render = true)
            showOwnImages()
        }
        if (images.isEmpty()) rows += Row.Info(getString(R.string.images_none))
        images.forEach { image ->
            val mark = if (image.id == current) "✓  " else ""
            rows += Row.Action("$mark${image.name}", closeAfter = false) {
                prefs.wallpaper = image.id
                loadWallpaper(render = true)
                showOwnImages()
            }
        }
        rows += Row.Action(getString(R.string.images_help), closeAfter = false) { showImagesHelp() }
        rows += Row.Action(getString(R.string.back), closeAfter = false) { showCustomize(INDEX_IMAGES) }
        // Foco na imagem em uso (ou na primeira)
        val focus = rows.indexOfFirst { it is Row.Action && it.label.startsWith("✓") }
            .takeIf { it >= 0 } ?: 0
        openMenu(getString(R.string.set_own_images), rows, focus, back = { showCustomize(INDEX_IMAGES) })
    }

    private fun showImagesHelp() {
        val rows = listOf(
            Row.Info(getString(R.string.images_help_1)),
            Row.Info(getString(R.string.images_help_usb)),
            Row.Action(getString(R.string.back), closeAfter = false) { showOwnImages() },
        )
        openMenu(getString(R.string.images_help_title), rows, back = { showOwnImages() })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_IMAGES && menu.isOpen) showOwnImages()
    }

    private fun showAbout() {
        val filterOn = FilterGuard.isEnabled(this)
        val rows = listOf(
            Row.Info(getString(R.string.about_author)),
            Row.Info(getString(R.string.about_made_with)),
            Row.Info(getString(R.string.about_version, BuildConfig.VERSION_NAME)),
            Row.Info(getString(R.string.about_filter,
                getString(if (filterOn) R.string.filter_on else R.string.filter_off))),
            Row.Action(getString(R.string.back), closeAfter = false) { showCustomize(INDEX_ABOUT) },
        )
        openMenu(getString(R.string.set_about), rows, back = { showCustomize(INDEX_ABOUT) })
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun isDpad(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    private companion object {
        const val TAG = "HY320Launcher"
        const val STALE_KEY_MS = 200L
        const val HOLD_BEFORE_REPEAT_MS = 700L
        const val REPEAT_INTERVAL_MS = 150L
        const val REQUEST_IMAGES = 1
        const val SHELF_FAVORITES = "shelf:favorites"
        const val SHELF_RECENTS = "shelf:recents"

        /** Raio do desfoque na imagem reduzida (baixo, médio, alto). */
        val BLUR_RADIUS = intArrayOf(2, 4, 7)

        // Posição das linhas no menu Personalizar (para voltar ao mesmo lugar)
        const val INDEX_HIDDEN = 11
        const val INDEX_IMAGES = 12
        const val INDEX_ABOUT = 13

        // Imagem do usuário ilegível no boot: tenta a cada 2 s por até 1 min
        const val WALLPAPER_RETRIES = 30
        const val WALLPAPER_RETRY_MS = 2000L
    }
}
