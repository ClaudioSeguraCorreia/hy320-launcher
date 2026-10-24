package dev.hy320.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.provider.Settings
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val key: String,
    val label: String,
    val image: Drawable?,
    /** null = ação interna do launcher (ex.: Personalizar). */
    val intent: Intent?,
    val isShortcut: Boolean = false,
)

/** Lê os apps instalados que podem ser abertos e monta os atalhos do sistema. */
object AppRepository {

    const val KEY_CUSTOMIZE = "__customize__"

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, AppEntry>()

        // Apps de TV (leanback) têm prioridade: usam banner horizontal
        for (ri in query(pm, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || pkg in byPackage) continue
            byPackage[pkg] = toEntry(pm, ri, Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        // Apps de celular que não têm versão TV
        for (ri in query(pm, Intent.CATEGORY_LAUNCHER)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || pkg in byPackage) continue
            byPackage[pkg] = toEntry(pm, ri, Intent.CATEGORY_LAUNCHER)
        }

        val collator = Collator.getInstance(Locale("pt", "BR"))
        return byPackage.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Atalhos fixos da linha "Sistema". Só inclui os que existem neste aparelho. */
    fun shortcuts(context: Context): List<AppEntry> {
        val pm = context.packageManager
        fun shortcut(key: String, labelRes: Int, iconRes: Int, intent: Intent?): AppEntry? {
            if (intent != null) {
                val ri = pm.resolveActivity(intent, 0) ?: return null
                if (!ri.activityInfo.exported) return null
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return AppEntry(key, context.getString(labelRes), context.getDrawable(iconRes), intent, isShortcut = true)
        }
        return listOfNotNull(
            shortcut("__settings__", R.string.settings, R.drawable.ic_settings, Intent(Settings.ACTION_SETTINGS)),
            shortcut("__wifi__", R.string.wifi, R.drawable.ic_wifi, Intent(Settings.ACTION_WIFI_SETTINGS)),
            shortcut("__bluetooth__", R.string.bluetooth, R.drawable.ic_bluetooth, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            // Seletor de entradas do fabricante (Allwinner/Softwinner)
            shortcut("__inputs__", R.string.inputs, R.drawable.ic_input,
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName(INPUTS_PKG, "$INPUTS_PKG.MainActivity"))),
            shortcut(KEY_CUSTOMIZE, R.string.customize, R.drawable.ic_customize, null),
        )
    }

    private const val INPUTS_PKG = "com.softwinner.awsource"

    private fun query(pm: PackageManager, category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun toEntry(pm: PackageManager, ri: ResolveInfo, category: String): AppEntry {
        val info = ri.activityInfo
        val image = info.loadBanner(pm)
            ?: info.applicationInfo.loadBanner(pm)
            ?: ri.loadIcon(pm)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return AppEntry(info.packageName, ri.loadLabel(pm).toString(), image, intent)
    }
}
