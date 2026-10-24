package dev.hy320.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Mantém o Filtro do controle ligado. O app de Configurações da HTC desliga
 * todos os serviços de acessibilidade ao abrir Data/hora e Idioma; este guarda
 * percebe e religa. Exige WRITE_SECURE_SETTINGS, concedida via ADB:
 *   pm grant dev.hy320.launcher android.permission.WRITE_SECURE_SETTINGS
 *
 * Só age se o usuário deixou o filtro ligado em Personalizar. Escreve apenas
 * as duas chaves de acessibilidade e preserva outros serviços que estiverem ativos.
 */
object FilterGuard {

    private const val TAG = "HY320Guarda"
    private const val SEPARATOR = ':'
    private const val SETTLE_MS = 150L

    private val restoreTimes = ArrayDeque<Long>()
    private var observing = false
    private val handler = Handler(Looper.getMainLooper())

    fun component(context: Context) =
        ComponentName(context, KeyFilterService::class.java).flattenToString()

    fun canWrite(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean {
        val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return services.orEmpty().split(SEPARATOR).any { it.equals(component(context), ignoreCase = true) } &&
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }

    /** Começa a vigiar as configurações e já corrige o estado atual. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!observing) {
            observing = true
            // O sistema muda as duas chaves em sequência: agrupa as notificações
            // próximas e corrige uma vez só
            val sync = Runnable { sync(app) }
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(sync)
                    handler.postDelayed(sync, SETTLE_MS)
                }
            }
            val resolver = app.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer)
        }
        sync(app)
    }

    /** Deixa o sistema de acordo com a preferência do usuário. */
    fun sync(context: Context) {
        if (!canWrite(context)) return
        val wanted = Prefs(context).filterEnabled
        if (wanted == isEnabled(context)) return
        if (wanted && tooManyRestores()) {
            Log.w(TAG, "Muitas religações seguidas; pausa para não brigar com o sistema")
            return
        }
        write(context, wanted)
        Log.i(TAG, if (wanted) "Filtro religado" else "Filtro desligado pelo usuário")
    }

    private fun write(context: Context, enable: Boolean) {
        val resolver = context.contentResolver
        val me = component(context)
        val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(SEPARATOR)
            .filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
        val services = if (enable) others + me else others
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(SEPARATOR.toString()))
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (services.isEmpty()) 0 else 1)
    }

    /**
     * Abrir telas da HTC causa uma religação de vez em quando. Mais de 8 em
     * 30 s indica um conflito contínuo: melhor parar do que brigar com o sistema.
     */
    private fun tooManyRestores(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (restoreTimes.isNotEmpty() && now - restoreTimes.first() > 30_000) restoreTimes.removeFirst()
        if (restoreTimes.size >= 8) return true
        restoreTimes.addLast(now)
        return false
    }
}
