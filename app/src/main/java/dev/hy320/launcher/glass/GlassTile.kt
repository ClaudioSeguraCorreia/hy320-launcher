package dev.hy320.launcher.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Vidro focável (apps, botões, linhas de menu). Ao receber foco: cresce com um
 * leve "quique" líquido, flutua com brilho na cor de destaque e uma faixa de luz
 * atravessa o vidro. A intensidade segue o ajuste de Animações.
 */
class GlassTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassLayout(context, attrs) {

    override val reactsToFocus = true

    /** Linhas de menu crescem menos que os blocos de apps. */
    var focusScale = 1.09f

    private var sweepAnimator: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
        applyGlow()
        // Desenha o bloco uma vez numa textura; a cada quadro a GPU só a "cola"
        // (e a escala de foco não exige redesenhar o conteúdo)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun refreshStyle() {
        applyGlow()
        super.refreshStyle()
    }

    private fun applyGlow() {
        outlineSpotShadowColor = Glass.accentColor
        outlineAmbientShadowColor = Glass.accentColor
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val level = Glass.animations
        val scale = when {
            !gainFocus || level == 0 -> 1f
            level == 1 -> 1f + (focusScale - 1f) * 0.5f
            else -> focusScale
        }
        val lift = if (gainFocus && level > 0) 14 * density else 0f
        animate().scaleX(scale).scaleY(scale).translationZ(lift)
            .setDuration(if (level == 2) 260L else 140L)
            .setInterpolator(if (gainFocus && level == 2) OvershootInterpolator(0.9f) else DecelerateInterpolator())
            .start()

        sweepAnimator?.cancel()
        sweepProgress = -1f
        if (gainFocus && level == 2) {
            sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 720
                startDelay = 70
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { sweepProgress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        sweepProgress = -1f
                    }
                })
                start()
            }
        }
        invalidate()
    }
}
