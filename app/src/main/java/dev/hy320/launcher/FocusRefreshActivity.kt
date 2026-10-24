package dev.hy320.launcher

import android.app.Activity
import android.os.Bundle

/**
 * Janela invisível que abre e fecha na hora. Serve para devolver o foco de
 * janela ao launcher: no Android 11, ligar o Filtro do controle (filtro de
 * teclas da acessibilidade) esvazia a fila de entrada e pode descartar o aviso
 * "ganhou foco" da janela que estava abrindo. Sem esse aviso, a tela ignora
 * todas as teclas ("Dropping event due to no window focus").
 */
class FocusRefreshActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        finish()
        overridePendingTransition(0, 0)
    }
}
