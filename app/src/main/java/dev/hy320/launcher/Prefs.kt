package dev.hy320.launcher

import android.content.Context
import dev.hy320.launcher.glass.Glass
import dev.hy320.launcher.glass.Wallpapers

/** Preferências do usuário, guardadas localmente no projetor. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Pacotes favoritos, na ordem escolhida. */
    var favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, "")!!.split('\n').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_FAVORITES, value.joinToString("\n")).apply()

    /** Apps abertos pelo launcher, do mais recente para o mais antigo. */
    var recents: List<String>
        get() = sp.getString(KEY_RECENTS, "")!!.split('\n').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_RECENTS, value.joinToString("\n")).apply()

    fun recordLaunch(pkg: String) {
        recents = (listOf(pkg) + (recents - pkg)).take(MAX_RECENTS)
    }

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, value).apply()

    /** Imagem própria de fundo ("file:..."); vazio = fundo de cores do tema. */
    var wallpaper: String
        get() = sp.getString("wallpaper", "")!!
        set(value) = sp.edit().putString("wallpaper", value).apply()

    val hasOwnImage get() = wallpaper.startsWith("file:")

    /**
     * Tema de cores: tom do vidro com qualquer fundo. Até a v0.14 o tema era
     * guardado em "wallpaper"; esse valor vira o tema inicial.
     */
    var theme: String
        get() = sp.getString("theme", null) ?: wallpaper.takeIf { it.isNotEmpty() && !hasOwnImage } ?: "aurora"
        set(value) = sp.edit().putString("theme", value).apply()

    /** O que desenhar no fundo: a imagem própria ou o fundo do tema. */
    val background get() = if (hasOwnImage) wallpaper else theme

    /** 0..100 em passos de 10. */
    var transparency: Int
        get() = sp.getInt("transparency", 60)
        set(value) = sp.edit().putInt("transparency", value).apply()

    /** 0 baixo, 1 médio, 2 alto. */
    var blur: Int
        get() = sp.getInt("blur", 1)
        set(value) = sp.edit().putInt("blur", value).apply()

    var reflections: Int
        get() = sp.getInt("reflections", 1)
        set(value) = sp.edit().putInt("reflections", value).apply()

    var animations: Int
        get() = sp.getInt("animations", 2)
        set(value) = sp.edit().putInt("animations", value).apply()

    var accent: String
        get() = sp.getString("accent", Glass.accents[0].id)!!
        set(value) = sp.edit().putString("accent", value).apply()

    var lightMode: Boolean
        get() = sp.getBoolean("lightMode", false)
        set(value) = sp.edit().putBoolean("lightMode", value).apply()

    /** 0 grande, 1 médio, 2 pequeno, 3 mínimo. */
    var iconSize: Int
        get() = sp.getInt("iconSize", 1)
        set(value) = sp.edit().putInt("iconSize", value).apply()

    /** Prateleiras da tela inicial (desligadas, somem da tela; os dados continuam guardados). */
    var showFavorites: Boolean
        get() = sp.getBoolean("showFavorites", false)
        set(value) = sp.edit().putBoolean("showFavorites", value).apply()

    var showRecents: Boolean
        get() = sp.getBoolean("showRecents", false)
        set(value) = sp.edit().putBoolean("showRecents", value).apply()

    /** Filtro do controle ligado (o FilterGuard mantém o sistema de acordo). */
    var filterEnabled: Boolean
        get() = sp.getBoolean("filterEnabled", true)
        set(value) = sp.edit().putBoolean("filterEnabled", value).apply()

    /** Copia os ajustes visuais para o estado global do vidro. */
    fun applyToGlass() {
        Glass.transparency = transparency
        Glass.reflections = reflections
        Glass.animations = animations
        Glass.lightMode = lightMode
        Glass.accentColor = Glass.accent(accent).color
        Glass.themeTint = Wallpapers.tint(theme)
    }

    fun toggleFavorite(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
    }

    /** Move o favorito uma posição (delta -1 = esquerda, +1 = direita). */
    fun moveFavorite(pkg: String, delta: Int) {
        val list = favorites.toMutableList()
        val from = list.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, pkg)
        favorites = list
    }

    fun hide(pkg: String) {
        hidden = hidden + pkg
        favorites = favorites - pkg
        recents = recents - pkg
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val KEY_HIDDEN = "hidden"
        const val MAX_RECENTS = 8
    }
}
