package dev.hy320.launcher.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.hy320.launcher.R
import kotlin.math.min

/**
 * Superfície de vidro líquido. Camadas, de baixo para cima:
 * 1. o papel de parede desfocado exatamente atrás dela (desfoque pré-calculado,
 *    porque o Android 11 não tem desfoque em tempo real);
 * 2. o tom do vidro (controlado pela transparência);
 * 3. o reflexo especular no topo;
 * 4. a faixa de luz que atravessa ao receber foco;
 * Nos blocos focáveis tudo isso vai para uma camada de GPU (ver GlassTile).
 * 5. a borda iluminada em degradê.
 */
open class GlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    protected val density = resources.displayMetrics.density
    private val cornerRadius: Float
    private val pill: Boolean

    /** Superfícies focáveis ganham borda de destaque e mais brilho. */
    protected open val reactsToFocus = false

    /** Progresso da faixa de luz (0..1); fora disso, não desenha. */
    var sweepProgress = -1f
        set(value) {
            field = value
            invalidate()
        }

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val location = IntArray(2)
    private val shaderMatrix = Matrix()
    private var shaderSource: Bitmap? = null
    private var gradientKey = ""

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
        cornerRadius = a.getDimension(R.styleable.GlassLayout_glassRadius, 16 * density)
        pill = a.getBoolean(R.styleable.GlassLayout_glassPill, false)
        a.recycle()
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius())
            }
        }
    }

    fun radius() = if (pill) min(width, height) / 2f else cornerRadius

    /** Chamado quando o usuário muda algum ajuste visual. */
    open fun refreshStyle() {
        gradientKey = ""
        invalidate()
    }

    /**
     * Ao entrar na tela, redesenha para alinhar o desfoque à nova posição.
     * Durante a rolagem não redesenha (economiza GPU); quem rola chama
     * Glass.invalidateTree ao parar.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = radius()
        val focused = reactsToFocus && isFocused
        rect.set(0f, 0f, w, h)

        // 1 + 2. Papel de parede desfocado atrás desta superfície, já com o tom do vidro
        val frosted = Glass.frosted
        if (!Glass.lightMode && frosted != null) {
            if (shaderSource !== frosted) {
                blurPaint.shader = BitmapShader(frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderSource = frosted
            }
            getLocationInWindow(location)
            shaderMatrix.setScale(
                Glass.screenWidth / frosted.width.toFloat(),
                Glass.screenHeight / frosted.height.toFloat(),
            )
            shaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            // Compensa a escala da animação de foco: o fundo fica parado
            shaderMatrix.postScale(1f / scaleX, 1f / scaleY)
            blurPaint.shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, r, r, blurPaint)
        } else {
            // Modo leve (ou fundo ainda carregando): vidro escuro translúcido, sem desfoque,
            // com um toque da cor do tema; mais opaco sobre fundos claros
            val t = Glass.transparency / 100f * (1f - Glass.darken)
            fillPaint.color = Color.argb(lerp(235, 140, t), 22, 26, 36)
            canvas.drawRoundRect(rect, r, r, fillPaint)
            fillPaint.color = Glass.withAlpha(Glass.themeTint, 40)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
        if (focused) {
            fillPaint.color = Color.argb(38, 255, 255, 255)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }

        ensureGradients(w, h, focused)

        // 3. Reflexo especular no topo
        if (Glass.reflections > 0) canvas.drawRoundRect(rect, r, r, glossPaint)

        // 4. Faixa de luz atravessando o vidro. Desenhada já no formato arredondado:
        // recortar com clipPath custaria caro na GPU do projetor
        val p = sweepProgress
        if (p in 0f..1f) {
            val x = -w * 0.6f + p * w * 2.2f
            sweepPaint.shader = LinearGradient(
                x, 0f, x + w * 0.45f, h,
                intArrayOf(Color.TRANSPARENT, Color.argb(80, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, r, r, sweepPaint)
        }

        // 5. Borda iluminada
        val inset = borderPaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, borderPaint)
    }

    /** Recria gradientes só quando tamanho, foco ou ajustes mudam. */
    private fun ensureGradients(w: Float, h: Float, focused: Boolean) {
        val key = "$w|$h|$focused|${Glass.version}"
        if (key == gradientKey) return
        gradientKey = key

        val glossAlpha = if (Glass.reflections == 2) 72 else 36
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            Color.argb(glossAlpha, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )

        val accent = Glass.accentColor
        if (focused) {
            borderPaint.strokeWidth = 2.5f * density
            borderPaint.shader = LinearGradient(
                0f, 0f, w, h, accent, (accent and 0x00FFFFFF) or (0x70 shl 24), Shader.TileMode.CLAMP,
            )
        } else {
            borderPaint.strokeWidth = 1.1f * density
            borderPaint.shader = LinearGradient(
                0f, 0f, w, h,
                Color.argb(150, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP,
            )
        }
    }

    private fun lerp(from: Int, to: Int, t: Float) = (from + (to - from) * t).toInt()
}
