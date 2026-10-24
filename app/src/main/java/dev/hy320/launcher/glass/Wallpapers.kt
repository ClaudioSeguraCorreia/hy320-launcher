package dev.hy320.launcher.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Environment
import java.io.File
import kotlin.math.max

/**
 * Papéis de parede: alguns originais, desenhados pelo próprio app (sem imagens
 * de terceiros), e as imagens do usuário.
 */
object Wallpapers {

    data class Option(val id: String, val name: String)

    private class Glow(val x: Float, val y: Float, val radius: Float, val color: Int)
    /** [tint]: cor que o tema dá ao vidro, com qualquer fundo. */
    private class Builtin(
        val id: String,
        val name: String,
        val top: Int,
        val bottom: Int,
        val glows: List<Glow>,
        val tint: Int,
    )

    private val builtins = listOf(
        Builtin("aurora", "Aurora", 0xFF0A0F24.toInt(), 0xFF1A1240.toInt(), listOf(
            Glow(0.18f, 0.28f, 0.55f, 0xD02EC4B6.toInt()),
            Glow(0.78f, 0.22f, 0.50f, 0xD07B5CFF.toInt()),
            Glow(0.58f, 0.92f, 0.48f, 0xC0FF6FB5.toInt()),
        ), tint = 0xFF7B5CFF.toInt()),
        Builtin("oceano", "Oceano", 0xFF021626.toInt(), 0xFF0A3550.toInt(), listOf(
            Glow(0.25f, 0.75f, 0.55f, 0xC000B4D8.toInt()),
            Glow(0.80f, 0.30f, 0.50f, 0xC03A86FF.toInt()),
            Glow(0.50f, 0.05f, 0.35f, 0x9090E0EF.toInt()),
        ), tint = 0xFF1E88D8.toInt()),
        Builtin("crepusculo", "Crepúsculo", 0xFF22091F.toInt(), 0xFF3F1624.toInt(), listOf(
            Glow(0.20f, 0.85f, 0.55f, 0xD0FF8C42.toInt()),
            Glow(0.75f, 0.70f, 0.45f, 0xC0D7263D.toInt()),
            Glow(0.60f, 0.15f, 0.40f, 0x90FFD166.toInt()),
        ), tint = 0xFFE0633A.toInt()),
        Builtin("floresta", "Floresta", 0xFF06120D.toInt(), 0xFF0E2A20.toInt(), listOf(
            Glow(0.30f, 0.30f, 0.50f, 0xB02D936C.toInt()),
            Glow(0.80f, 0.80f, 0.50f, 0xA0A7C957.toInt()),
            Glow(0.65f, 0.20f, 0.35f, 0x901B998B.toInt()),
        ), tint = 0xFF2D936C.toInt()),
        Builtin("grafite", "Grafite", 0xFF0C0D10.toInt(), 0xFF1A1D24.toInt(), listOf(
            Glow(0.25f, 0.30f, 0.50f, 0x803A3F4B.toInt()),
            Glow(0.80f, 0.75f, 0.50f, 0x804A6FA5.toInt()),
        ), tint = 0xFF4A5060.toInt()),
    )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** Nome da pasta de imagens, no armazenamento interno e na raiz do pendrive. */
    const val FOLDER = "Wallpapers"

    /** Pasta pública para o usuário copiar imagens (exige permissão de leitura). */
    fun publicFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)

    fun imagesIn(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExtensions }.orEmpty()

    /** Temas de cores (cada um com seu fundo desenhado pelo próprio launcher). */
    fun themes(): List<Option> = builtins.map { Option(it.id, it.name) }

    fun tint(themeId: String): Int = (builtins.firstOrNull { it.id == themeId } ?: builtins[0]).tint

    fun userImages(context: Context): List<Option> =
        listOfNotNull(context.getExternalFilesDir("wallpapers"), publicFolder())
            .flatMap(::imagesIn)
            .sortedBy { it.name.lowercase() }
            .map { Option("file:${it.absolutePath}", it.nameWithoutExtension) }

    /**
     * null quando a imagem do usuário não pôde ser lida. No boot isso é normal:
     * o launcher abre alguns segundos antes de o armazenamento ser montado.
     */
    fun render(id: String, width: Int, height: Int): Bitmap? {
        if (id.startsWith("file:")) return decodeImage(File(id.removePrefix("file:")), width, height)
        val builtin = builtins.firstOrNull { it.id == id } ?: builtins[0]
        // Gradientes suaves: meia resolução basta e economiza memória
        return draw(builtin, width / 2, height / 2)
    }

    private fun draw(b: Builtin, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), b.top, b.bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val size = max(w, h)
        for (g in b.glows) {
            val cx = g.x * w
            val cy = g.y * h
            val r = g.radius * size
            paint.shader = RadialGradient(cx, cy, r, g.color, g.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        return bitmap
    }

    /** Lê a imagem já reduzida e recortada para preencher a tela (RGB_565: metade da memória). */
    private fun decodeImage(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val source = BitmapFactory.decodeFile(file.path, options) ?: return null
        val scale = max(w / source.width.toFloat(), h / source.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - source.width * scale) / 2f, (h - source.height * scale) / 2f)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(result).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return result
    }
}
