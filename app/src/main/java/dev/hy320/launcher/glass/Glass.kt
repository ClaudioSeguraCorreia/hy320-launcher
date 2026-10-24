package dev.hy320.launcher.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup

data class Accent(val id: String, val name: String, val color: Int)

/** Estado visual do vidro, compartilhado por todas as superfícies da tela. */
object Glass {

    val accents = listOf(
        Accent("gelo", "Azul gelo", 0xFF8FD3FF.toInt()),
        Accent("menta", "Menta", 0xFF8EF0C6.toInt()),
        Accent("ambar", "Âmbar", 0xFFFFCF7A.toInt()),
        Accent("rosa", "Rosa", 0xFFFFA3D1.toInt()),
        Accent("lavanda", "Lavanda", 0xFFB9A6FF.toInt()),
        Accent("branco", "Branco", 0xFFFFFFFF.toInt()),
    )

    fun accent(id: String?) = accents.firstOrNull { it.id == id } ?: accents[0]

    /** Papel de parede reduzido e desfocado; null enquanto carrega. */
    var blurred: Bitmap? = null
        set(value) {
            field = value
            updateFrosted()
        }

    /**
     * Desfoque já com o tom do vidro aplicado. Uma camada a menos por
     * superfície: a GPU do projetor é o gargalo, não o processador.
     */
    var frosted: Bitmap? = null
        private set

    /** Tamanho da janela, para alinhar o desfoque com o fundo. */
    var screenWidth = 1
    var screenHeight = 1

    /** 0 = vidro bem fosco; 100 = quase transparente. */
    var transparency = 60

    /** 0 desligados, 1 suaves, 2 intensos. */
    var reflections = 1

    /** 0 desligadas, 1 reduzidas, 2 completas. */
    var animations = 2

    /** Sem desfoque, só translucidez: alternativa mais leve. */
    var lightMode = false

    var accentColor = accents[0].color

    /** Cor do tema, misturada ao vidro. */
    var themeTint = 0xFF7B5CFF.toInt()

    /**
     * Quanto escurecer o vidro (0..1), conforme o brilho do fundo. Fundos claros
     * deixariam o texto branco ilegível; fundos escuros não mudam.
     */
    var darken = 0f
        private set

    /** Muda a cada ajuste; as superfícies refazem seus gradientes quando muda. */
    var version = 0
        private set

    fun changed(root: View) {
        version++
        updateFrosted()
        invalidateTree(root)
    }

    /** Tom branco do vidro em função da transparência (0 = bem fosco). */
    fun tintAlpha() = (92 + (12 - 92) * transparency / 100f).toInt()

    private fun updateFrosted() {
        val source = blurred ?: return
        darken = darkenFor(source)
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).apply {
            // Fundo claro: menos branco e mais sombra, para o texto continuar legível
            drawColor(Color.argb((tintAlpha() * (1f - darken)).toInt(), 255, 255, 255))
            drawColor(withAlpha(themeTint, THEME_ALPHA))
            if (darken > 0f) drawColor(Color.argb((darken * MAX_DARKEN).toInt(), 0, 0, 0))
        }
        frosted = result
    }

    /** Brilho médio do fundo desfocado: 0 até 45% de luminância, 1 em fundo branco. */
    private fun darkenFor(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        for (p in pixels) sum += 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)
        val luminance = (sum / pixels.size / 255.0).toFloat()
        return ((luminance - 0.45f) / 0.55f).coerceIn(0f, 1f)
    }

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private const val THEME_ALPHA = 46
    private const val MAX_DARKEN = 170

    fun invalidateTree(view: View) {
        if (view is GlassLayout) view.refreshStyle()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateTree(view.getChildAt(i))
    }
}
