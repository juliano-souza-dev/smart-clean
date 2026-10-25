package com.juliano.limpacel

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

data class DeviceUsage(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

data class MegaFile(
    val path: String,
    val name: String,
    val bytes: Long,
    val modified: Long
) {
    val folder: String get() = path.substringBeforeLast('/', "")
    val kind: FileKind get() = classifyFile(this)
    val isInstaller: Boolean get() = kind == FileKind.APK
    val isTemporary: Boolean get() = listOf(
        ".tmp", ".temp", ".log", ".bak", ".old", ".part", ".crdownload"
    ).any { name.endsWith(it, ignoreCase = true) }
}

enum class FileKind(val title: String) {
    PHOTO("Fotos"), SCREENSHOT("Capturas"), VIDEO("Vídeos"),
    SCREEN_RECORDING("Gravações de tela"), MUSIC("Músicas"), AUDIO("Áudios"),
    PDF("PDFs"), DOCUMENT("Documentos"), ARCHIVE("Compactados"),
    APK("APKs"), OTHER("Outros")
}

private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng", "avif")
private val videoExt = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts")
private val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid")
private val docExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "epub", "csv", "json")
private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

fun classifyFile(file: MegaFile): FileKind {
    val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val path = file.path.lowercase(Locale.ROOT)
    return when {
        ext in imageExt && (path.contains("screenshot") || path.contains("screen_shot") || path.contains("captura")) -> FileKind.SCREENSHOT
        ext in imageExt -> FileKind.PHOTO
        ext in videoExt && (path.contains("screenrecord") || path.contains("screen_record") || path.contains("screen-record") || path.contains("gravacao de tela")) -> FileKind.SCREEN_RECORDING
        ext in videoExt -> FileKind.VIDEO
        ext in audioExt && (path.contains("/music/") || path.contains("/musicas/") || path.contains("/música/")) -> FileKind.MUSIC
        ext in audioExt -> FileKind.AUDIO
        ext == "pdf" -> FileKind.PDF
        ext in docExt -> FileKind.DOCUMENT
        ext in archiveExt -> FileKind.ARCHIVE
        ext == "apk" -> FileKind.APK
        else -> FileKind.OTHER
    }
}

data class MegaProgress(
    val stage: String,
    val files: Int,
    val bytes: Long,
    val location: String
)

data class MegaReport(
    val files: List<MegaFile>,
    val duplicateGroups: List<List<MegaFile>>,
    val unreadableFolders: Int,
    val incomplete: Boolean,
    val hashLimitReached: Boolean,
    val roots: List<String>
) {
    val largeFiles: List<MegaFile> get() = files.filter { it.bytes >= MegaScanner.LARGE_BYTES }
    val duplicatePaths: Set<String> get() = duplicateGroups.flatten().mapTo(hashSetOf()) { it.path }
    val installers: List<MegaFile> get() = files.filter { it.isInstaller }
    val temporaryCandidates: List<MegaFile> get() = files.filter { it.isTemporary }
    val indexedBytes: Long get() = files.sumOf { it.bytes }
}

data class MegaDeletion(val deleted: Int, val failed: Int, val freedBytes: Long)

/**
 * Varredura do armazenamento compartilhado. Exige autorização explícita
 * MANAGE_EXTERNAL_STORAGE do usuário (Android 11+).
 * Não promete ler /Android/data, /Android/obb nem os dados privados dos apps.
 */
class MegaScanner(private val context: Context) {
    companion object {
        const val LARGE_BYTES = 50L * 1024L * 1024L
        private const val FILE_LIMIT = 150_000
        private const val DIRECTORY_LIMIT = 30_000
        private const val HASH_BUDGET = 16L * 1024L * 1024L * 1024L
        private const val MAX_HASH_FILE = 2L * 1024L * 1024L * 1024L
    }

    fun usage(): DeviceUsage {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return DeviceUsage(stat.totalBytes, stat.availableBytes)
    }

    fun sharedRoots(): List<File> {
        val storage = context.getSystemService(StorageManager::class.java)
        val roots = storage.storageVolumes.mapNotNull { volume ->
            if (volume.state == Environment.MEDIA_MOUNTED) volume.directory else null
        }.filter { it.isDirectory }
        val fallback = Environment.getExternalStorageDirectory()
        return (roots + fallback).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.path }
    }

    suspend fun scan(onProgress: suspend (MegaProgress) -> Unit): MegaReport = withContext(Dispatchers.IO) {
        check(Environment.isExternalStorageManager()) { "Autorize o acesso aos arquivos antes do Mega Scan." }
        val roots = sharedRoots()
        val files = ArrayList<MegaFile>()
        val pending = ArrayDeque<File>()
        roots.forEach { pending.addLast(it) }
        var unreadable = 0
        var folders = 0
        var incomplete = false
        var lastUpdate = 0L
        var indexedBytes = 0L

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++folders > DIRECTORY_LIMIT || files.size >= FILE_LIMIT) {
                incomplete = true
                break
            }
            val directory = pending.removeFirst()
            if (isProtectedDirectory(directory)) continue
            val entries = try { directory.listFiles() } catch (_: Exception) { null }
            if (entries == null) {
                unreadable++
                continue
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (!isProtectedDirectory(entry)) pending.addLast(entry)
                } else if (entry.isFile) {
                    if (files.size >= FILE_LIMIT) { incomplete = true; break }
                    val size = entry.length().coerceAtLeast(0L)
                    files += MegaFile(entry.absolutePath, entry.name, size, entry.lastModified())
                    indexedBytes += size
                }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 350) {
                onProgress(MegaProgress("Varrendo pastas", files.size, indexedBytes, directory.absolutePath))
                lastUpdate = now
            }
        }

        onProgress(MegaProgress("Comparando conteúdos duplicados", files.size, indexedBytes, "Verificando arquivos"))
        val duplicateGroups = mutableListOf<List<MegaFile>>()
        val sameSizeGroups = files.asSequence()
            .filter { it.bytes > 0L }
            .groupBy { it.bytes }
            .filterValues { it.size >= 2 }
        var hashedBytes = 0L
        var hashingLimited = false
        lastUpdate = 0L

        for ((size, candidates) in sameSizeGroups) {
            currentCoroutineContext().ensureActive()
            if (size > MAX_HASH_FILE) {
                hashingLimited = true
                continue
            }
            val hashes = hashMapOf<String, MutableList<MegaFile>>()
            for (item in candidates) {
                currentCoroutineContext().ensureActive()
                if (hashedBytes + size > HASH_BUDGET) {
                    hashingLimited = true
                    break
                }
                hashedBytes += size
                val digest = sha256(File(item.path)) ?: continue
                hashes.getOrPut(digest) { mutableListOf() }.add(item)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 350) {
                    onProgress(MegaProgress("Comparando duplicados", files.size, indexedBytes, item.name))
                    lastUpdate = now
                }
            }
            duplicateGroups += hashes.values.filter { it.size >= 2 }
        }

        MegaReport(
            files = files.sortedByDescending { it.bytes },
            duplicateGroups = duplicateGroups,
            unreadableFolders = unreadable,
            incomplete = incomplete,
            hashLimitReached = hashingLimited,
            roots = roots.map { it.absolutePath }
        )
    }

    fun delete(files: List<MegaFile>): MegaDeletion {
        check(Environment.isExternalStorageManager()) { "O acesso aos arquivos foi revogado." }
        val roots = sharedRoots().mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        var deleted = 0
        var failed = 0
        var freed = 0L
        for (snapshot in files.distinctBy { it.path }) {
            val path = snapshot.path
            val success = runCatching {
                val file = File(path).canonicalFile
                val isWithinSharedStorage = roots.any { root ->
                    file.path.startsWith("$root${File.separator}")
                }
                if (!isWithinSharedStorage || isProtectedDirectory(file) || !file.isFile ||
                    file.length() != snapshot.bytes || file.lastModified() != snapshot.modified) false
                else {
                    val size = file.length()
                    val didDelete = file.delete()
                    if (didDelete) freed += size
                    didDelete
                }
            }.getOrDefault(false)
            if (success) deleted++ else failed++
        }
        return MegaDeletion(deleted, failed, freed)
    }

    private fun isProtectedDirectory(file: File): Boolean {
        // O Android protege as áreas privadas dos aplicativos; não tentar contornar.
        val normal = file.absolutePath.replace('\\', '/').lowercase(Locale.ROOT)
        return normal.contains("/android/data/") || normal.endsWith("/android/data") ||
            normal.contains("/android/obb/") || normal.endsWith("/android/obb")
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Exception) { null }
}

fun readableSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var n = bytes.toDouble()
    var i = -1
    do { n /= 1024.0; i++ } while (n >= 1024.0 && i < units.lastIndex)
    return "%.1f %s".format(Locale("pt", "BR"), n, units[i])
}
