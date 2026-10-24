package dev.hy320.launcher.glass

import android.graphics.Bitmap

/**
 * Desfoque por caixa em 3 passadas (resultado próximo do gaussiano).
 * Roda uma única vez por papel de parede, sobre uma imagem pequena (1/8 da tela),
 * por isso é barato mesmo no processador do projetor.
 */
object BoxBlur {

    fun blur(source: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        val w = source.width
        val h = source.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        source.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            pass(a, b, w, h, radius, horizontal = true)
            pass(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Média móvel numa direção; as bordas repetem o último pixel. */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            fun at(i: Int) = src[start + i.coerceIn(0, length - 1) * step]
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until length) {
                dst[start + i * step] =
                    (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = at(i - r)
                val inn = at(i + r + 1)
                sr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
