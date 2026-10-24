package dev.hy320.launcher

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * "Filtro do controle": serviço de acessibilidade que recebe as teclas antes
 * de qualquer app. Não lê o conteúdo da tela; só usa os avisos de "foco mudou".
 *
 * Duas regras para as setas:
 * 1. Atrasada: chegou aqui com mais de [STALE_KEY_MS] de atraso (presa na fila
 *    enquanto o sistema travava) → descarta.
 * 2. Uma seta por vez: depois de liberar uma seta, espera o app avisar que o
 *    foco mudou antes de liberar a próxima. Se o app está lento, as setas extras
 *    são descartadas em vez de acumular e "disparar" depois. Só vale para apps
 *    que já mostraram que avisam (aprendido em uso), e nunca espera mais que
 *    [MAX_WAIT_MS].
 */
class KeyFilterService : AccessibilityService() {

    private var currentPackage: String? = null

    /** Seta liberada e ainda sem resposta do app. */
    private var pendingSince = 0L
    private var pending = false

    /** Por app: true = avisa mudanças de foco (dá para esperar por ele). */
    private val emitsFocus = HashMap<String, Boolean>()
    private val timeouts = HashMap<String, Int>()

    private var droppedStale = 0
    private var droppedBusy = 0

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !isArrow(event.keyCode)) return false
        val now = SystemClock.uptimeMillis()

        val age = now - event.eventTime
        if (age > STALE_KEY_MS) {
            droppedStale++
            Log.d(TAG, "Seta atrasada descartada: ${name(event)} (${age} ms; atrasadas=$droppedStale)")
            return true
        }

        val pkg = currentPackage
        if (pending && pkg != null) {
            val waited = now - pendingSince
            if (waited < MAX_WAIT_MS && emitsFocus[pkg] == true) {
                droppedBusy++
                Log.d(TAG, "Seta descartada, $pkg ainda processando a anterior: ${name(event)} " +
                    "(${waited} ms; ocupado=$droppedBusy)")
                return true
            }
            if (waited >= MAX_WAIT_MS) onTimeout(pkg)
        }

        pending = true
        pendingSince = now
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPackage = pkg
                pending = false
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // O app processou a seta: libera a próxima e aprende que ele avisa
                pending = false
                currentPackage = pkg
                if (emitsFocus[pkg] != true) Log.i(TAG, "$pkg avisa mudanças de foco: modo uma seta por vez")
                emitsFocus[pkg] = true
                timeouts[pkg] = 0
            }
        }
    }

    /** O app não respondeu: após 3 seguidas, deixa de esperar por ele. */
    private fun onTimeout(pkg: String) {
        val count = (timeouts[pkg] ?: 0) + 1
        timeouts[pkg] = count
        if (count >= 3 && emitsFocus[pkg] == true) {
            emitsFocus[pkg] = false
            Log.i(TAG, "$pkg não avisa foco de forma confiável: sem espera para ele")
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "Filtro do controle ativo (atraso máx. ${STALE_KEY_MS} ms, espera máx. ${MAX_WAIT_MS} ms)")
        // Ligar o filtro esvazia a fila de entrada; o launcher confere se perdeu o foco
        onConnected?.invoke()
    }

    companion object {
        /** Avisado (na thread principal) quando o filtro se conecta. */
        var onConnected: (() -> Unit)? = null

        private const val TAG = "HY320Filtro"
        // Um pouco acima do limite do launcher: aqui há uma troca de processo a mais
        private const val STALE_KEY_MS = 250L
        private const val MAX_WAIT_MS = 500L
    }

    override fun onInterrupt() = Unit

    private fun name(event: KeyEvent) = KeyEvent.keyCodeToString(event.keyCode)

    private fun isArrow(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }
}
