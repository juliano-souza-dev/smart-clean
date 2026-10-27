package com.juliano.limpacel

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TrashItem(
    val id: String,
    val originalPath: String,
    val storedPath: String,
    val bytes: Long,
    val trashedAt: Long
)

data class TrashResult(val outcomes: List<FileOutcome>) {
    val succeeded: Int get() = outcomes.count { it.success }
    val failed: Int get() = outcomes.size - succeeded
    val bytes: Long get() = outcomes.filter { it.success }.sumOf { it.bytes }
}

/** Lixeira no MESMO volume dos arquivos: permite restaurar, mas não libera espaço antes de esvaziar. */
class TrashManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("smart_clean_trash", Context.MODE_PRIVATE)

    @Synchronized fun items(): List<TrashItem> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            TrashItem(o.getString("id"), o.getString("original"), o.getString("stored"),
                o.getLong("bytes"), o.getLong("time"))
        }.sortedByDescending { it.trashedAt }
    }.getOrDefault(emptyList())

    @Synchronized private fun persist(items: List<TrashItem>): Boolean {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("original", item.originalPath)
                .put("stored", item.storedPath).put("bytes", item.bytes).put("time", item.trashedAt))
        }
        // Commit síncrono para evitar perder a localização de um arquivo movido.
        return prefs.edit().putString("items", array.toString()).commit()
    }

    @Synchronized fun move(files: List<MegaFile>): TrashResult {
        require(Environment.isExternalStorageManager()) { "O acesso aos arquivos foi revogado." }
        val scanner = MegaScanner(context)
        val roots = scanner.sharedRoots().map { it.canonicalFile }
        val excluded = ScanSettings(context).excludedFolders()
        val output = mutableListOf<FileOutcome>()
        for (snapshot in files.distinctBy { it.path }) {
            val success = runCatching {
                val source = File(snapshot.path).canonicalFile
                val root = roots.firstOrNull { ScanPolicy.isInside(source.path, it.path) }
                    ?: error("Fora do armazenamento permitido")
                check(source.path == snapshot.path) { "Caminho inesperado" }
                check(!ScanPolicy.protected(source.path, excluded)) { "Pasta protegida" }
                check(snapshotMatches(source, snapshot)) { "Arquivo alterado desde a análise" }
                val trashDir = File(root, ScanPolicy.TRASH_FOLDER)
                check(trashDir.isDirectory || trashDir.mkdir()) { "Não foi possível criar a lixeira" }
                // O Android não deve indexar os arquivos da lixeira como mídia normal.
                val nomedia = File(trashDir, ".nomedia")
                if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
                val id = UUID.randomUUID().toString()
                val target = File(trashDir, id)
                check(source.renameTo(target)) { "Não foi possível mover para a lixeira" }
                val newItem = TrashItem(id, source.path, target.path, snapshot.bytes, System.currentTimeMillis())
                if (!persist(items() + newItem)) {
                    // Sem registro da origem não deixamos o arquivo órfão na lixeira.
                    check(target.renameTo(source)) { "Falha crítica ao restaurar arquivo após erro no registro" }
                    error("Não foi possível salvar o registro da lixeira")
                }
                true
            }.getOrDefault(false)
            output += FileOutcome(snapshot.path, snapshot.bytes, success,
                if (success) "Na lixeira — pode restaurar" else "Não movido; verifique permissões e alterações")
        }
        return TrashResult(output)
    }

    @Synchronized fun restore(ids: Set<String>): TrashResult {
        require(Environment.isExternalStorageManager()) { "O acesso aos arquivos foi revogado." }
        val all = items()
        val outcomes = mutableListOf<FileOutcome>()
        for (item in all.filter { it.id in ids }) {
            val success = runCatching {
                val stored = validStoredFile(item)
                val original = File(item.originalPath)
                check(!original.exists()) { "Já existe arquivo no destino" }
                check(original.parentFile?.isDirectory == true) { "Pasta original indisponível" }
                check(stored.renameTo(original)) { "Não foi possível restaurar" }
                if (!persist(items().filterNot { it.id == item.id })) {
                    check(original.renameTo(stored)) { "Falha ao reverter restauração sem registro" }
                    error("Falha ao atualizar a lixeira")
                }
                true
            }.getOrDefault(false)
            outcomes += FileOutcome(item.originalPath, item.bytes, success,
                if (success) "Restaurado" else "Não restaurado: origem indisponível ou destino ocupado")
        }
        return TrashResult(outcomes)
    }

    @Synchronized fun permanentlyDelete(ids: Set<String>): TrashResult {
        require(Environment.isExternalStorageManager()) { "O acesso aos arquivos foi revogado." }
        val all = items()
        val outcomes = mutableListOf<FileOutcome>()
        for (item in all.filter { it.id in ids }) {
            val success = runCatching {
                val stored = validStoredFile(item)
                check(stored.delete()) { "Falha na exclusão" }
                persist(items().filterNot { it.id == item.id })
            }.getOrDefault(false)
            outcomes += FileOutcome(item.originalPath, item.bytes, success,
                if (success) "Excluído da lixeira" else "Não excluído")
        }
        return TrashResult(outcomes)
    }

    private fun validStoredFile(item: TrashItem): File {
        val source = File(item.storedPath).canonicalFile
        val trashDir = source.parentFile ?: error("Local inválido")
        check(trashDir.name == ScanPolicy.TRASH_FOLDER && source.name == item.id) { "Registro inválido" }
        check(MegaScanner(context).sharedRoots().any { root ->
            trashDir.canonicalPath == File(root, ScanPolicy.TRASH_FOLDER).canonicalPath
        }) { "Lixeira fora do armazenamento autorizado" }
        check(source.isFile && source.length() == item.bytes) { "Arquivo ausente ou alterado" }
        return source
    }
}
