package com.juliano.limpacel

import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
    val isHidden: Boolean get() = ScanPolicy.isHidden(path)
    val isThumbnail: Boolean get() = ScanPolicy.isThumbnail(path)
    val isAppMedia: Boolean get() = ScanPolicy.isAppMedia(path)
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
    val roots: List<String>,
    val largeThresholdBytes: Long = ScanPolicy.DEFAULT_LARGE_MB * 1024L * 1024L,
    val hiddenFolders: List<String> = emptyList(),
    val protectedFoldersSkipped: Int = 0,
    val mediaOutsideIndex: List<MegaFile> = emptyList(),
    val mediaIndexChecked: Boolean = false,
    val resourcesLimited: Boolean = false
) {
    val largeFiles: List<MegaFile> get() = files.filter { it.bytes >= largeThresholdBytes }
    val duplicatePaths: Set<String> get() = duplicateGroups.flatten().mapTo(hashSetOf()) { it.path }
    val installers: List<MegaFile> get() = files.filter { it.isInstaller }
    val temporaryCandidates: List<MegaFile> get() = files.filter { it.isTemporary }
    val hiddenFiles: List<MegaFile> get() = files.filter { it.isHidden }
    val thumbnailFiles: List<MegaFile> get() = files.filter { it.isThumbnail }
    val appMediaFiles: List<MegaFile> get() = files.filter { it.isAppMedia }
    val indexedBytes: Long get() = files.sumOf { it.bytes }
}

data class FileOutcome(val path: String, val bytes: Long, val success: Boolean, val detail: String = "")

data class MegaDeletion(
    val deleted: Int,
    val failed: Int,
    val freedBytes: Long,
    val outcomes: List<FileOutcome> = emptyList()
)

/** Confere o estado capturado pelo scan antes de qualquer exclusão. */
fun snapshotMatches(file: File, snapshot: MegaFile): Boolean =
    file.isFile && file.length() == snapshot.bytes && file.lastModified() == snapshot.modified

/**
 * Varredura do armazenamento compartilhado. Exige autorização explícita
 * MANAGE_EXTERNAL_STORAGE do usuário (Android 11+).
 * Não promete ler /Android/data, /Android/obb nem os dados privados dos apps.
 */
class MegaScanner(private val context: Context) {
    companion object {
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
        val settings = ScanSettings(context)
        val excluded = settings.excludedFolders()
        val roots = sharedRoots()
        val files = ArrayList<MegaFile>()
        val hiddenFolders = ArrayList<String>()
        val pending = ArrayDeque<File>()
        roots.forEach { pending.addLast(it) }
        var unreadable = 0
        var folders = 0
        var protectedSkipped = 0
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
            if (!roots.any { ScanPolicy.isInside(directory.canonicalPath, it.canonicalPath) }) {
                protectedSkipped++
                continue
            }
            if (ScanPolicy.protected(directory.absolutePath, excluded)) {
                protectedSkipped++
                continue
            }
            val entries = try { directory.listFiles() } catch (_: Exception) { null }
            if (entries == null) {
                unreadable++
                continue
            }
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                // Links simbólicos que apontam para fora da raiz não são seguidos.
                if (!roots.any { ScanPolicy.isInside(entry.canonicalPath, it.canonicalPath) }) {
                    protectedSkipped++
                    continue
                }
                if (entry.isDirectory) {
                    if (ScanPolicy.protected(entry.absolutePath, excluded)) {
                        protectedSkipped++
                    } else {
                        if (entry.name.startsWith('.') || entry.name.equals("thumbnails", true)) {
                            hiddenFolders += entry.absolutePath
                        }
                        pending.addLast(entry)
                    }
                } else if (entry.isFile) {
                    if (ScanPolicy.protected(entry.absolutePath, excluded)) continue
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
        val sameSizeGroups = ScanPolicy.sameSizeCandidates(files)
        var hashedBytes = 0L
        var hashingLimited = false
        var resourceLimited = false
        lastUpdate = 0L
        val powerManager = context.getSystemService(PowerManager::class.java)

        for ((size, candidates) in sameSizeGroups) {
            currentCoroutineContext().ensureActive()
            // Em alta pressão térmica, interrompe a etapa custosa de leitura total.
            if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                resourceLimited = true
                hashingLimited = true
                break
            }
            if (!settings.deepHashing && size > MAX_HASH_FILE) {
                hashingLimited = true
                continue
            }
            val hashes = hashMapOf<String, MutableList<MegaFile>>()
            for (item in candidates) {
                currentCoroutineContext().ensureActive()
                if (!settings.deepHashing && hashedBytes + size > HASH_BUDGET) {
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

        onProgress(MegaProgress("Comparando com a galeria", files.size, indexedBytes, "Índice de mídias"))
        val mediaIndex = readIndexedMediaPaths()
        val outsideIndex = if (mediaIndex == null) emptyList() else files.filter { file ->
            ScanPolicy.isMedia(file.kind) && ScanPolicy.normalized(file.path) !in mediaIndex
        }

        MegaReport(
            files = files.sortedByDescending { it.bytes },
            duplicateGroups = duplicateGroups,
            unreadableFolders = unreadable,
            incomplete = incomplete,
            hashLimitReached = hashingLimited,
            roots = roots.map { it.absolutePath },
            largeThresholdBytes = settings.largeFileMb * 1024L * 1024L,
            hiddenFolders = hiddenFolders,
            protectedFoldersSkipped = protectedSkipped,
            mediaOutsideIndex = outsideIndex,
            mediaIndexChecked = mediaIndex != null,
            resourcesLimited = resourceLimited
        )
    }

    fun delete(files: List<MegaFile>): MegaDeletion {
        check(Environment.isExternalStorageManager()) { "O acesso aos arquivos foi revogado." }
        val excluded = ScanSettings(context).excludedFolders()
        val roots = sharedRoots().mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        var deleted = 0
        var failed = 0
        var freed = 0L
        val outcomes = mutableListOf<FileOutcome>()
        for (snapshot in files.distinctBy { it.path }) {
            val path = snapshot.path
            val success = runCatching {
                val file = File(path).canonicalFile
                val isWithinSharedStorage = roots.any { root ->
                    file.path.startsWith("$root${File.separator}")
                }
                if (!isWithinSharedStorage || file.absolutePath != path ||
                    ScanPolicy.protected(file.path, excluded) || !snapshotMatches(file, snapshot)) false
                else {
                    val size = file.length()
                    val didDelete = file.delete()
                    if (didDelete) freed += size
                    didDelete
                }
            }.getOrDefault(false)
            if (success) deleted++ else failed++
            outcomes += FileOutcome(path, snapshot.bytes, success,
                if (success) "Excluído permanentemente" else "Não excluído: arquivo alterado, protegido ou indisponível")
        }
        return MegaDeletion(deleted, failed, freed, outcomes)
    }

    private suspend fun readIndexedMediaPaths(): Set<String>? {
        return try {
            val indexed = hashSetOf<String>()
            val uri = MediaStore.Files.getContentUri("external")
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?: return null
            cursor.use {
                val index = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (index < 0) return null
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    it.getString(index)?.let { value -> indexed += ScanPolicy.normalized(value) }
                }
            }
            indexed
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun sha256(file: File): String? = try {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        FileDigests.sha256(file) { job?.ensureActive() }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
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
