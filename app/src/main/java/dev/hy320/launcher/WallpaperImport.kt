package dev.hy320.launcher

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import dev.hy320.launcher.glass.Wallpapers
import java.io.File

/**
 * Copia imagens de pendrives para Pictures/Wallpapers, para continuarem
 * disponíveis depois que o pendrive for removido. Só olha a pasta "Wallpapers"
 * na raiz do pendrive (qualquer caixa: "wallpapers" também vale), para não
 * varrer fotos pessoais, e nunca sobrescreve arquivos.
 *
 * Usa o índice de mídia do Android: no Android 11, listar a pasta do pendrive
 * pelo caminho volta vazio para apps, mesmo com permissão de leitura.
 */
object WallpaperImport {

    private const val TAG = "HY320Imagens"

    /** Cria a pasta interna (se ainda não existe) e copia as imagens novas. Rodar fora da thread de tela. */
    fun run(context: Context): Int {
        val target = Wallpapers.publicFolder()
        if (!target.isDirectory && !target.mkdirs()) {
            // No boot, o armazenamento ainda pode estar montando; tenta de novo quando montar
            Log.w(TAG, "Pasta ${target.path} indisponível")
            return 0
        }
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { it.mediaStoreVolumeName }
        var copied = 0
        for (volume in volumes) {
            val images = MediaStore.Images.Media.getContentUri(volume)
            val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            // LIKE ignora maiúsculas/minúsculas; só a pasta da raiz, sem subpastas
            val query = context.contentResolver.query(
                images, columns, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Wallpapers.FOLDER}/"), null,
            ) ?: continue
            query.use { cursor ->
                Log.i(TAG, "Pendrive $volume: ${cursor.count} imagem(ns) na pasta ${Wallpapers.FOLDER}")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val dest = File(target, name)
                    if (dest.exists()) continue
                    try {
                        context.contentResolver.openInputStream(ContentUris.withAppendedId(images, cursor.getLong(0)))!!.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        copied++
                    } catch (e: Exception) {
                        // IOException, SecurityException ou pendrive removido no meio da cópia
                        Log.w(TAG, "Falha ao copiar $name", e)
                        dest.delete()
                    }
                }
            }
        }
        if (copied > 0) Log.i(TAG, "$copied imagem(ns) copiada(s) do pendrive")
        return copied
    }
}
