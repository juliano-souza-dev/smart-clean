package com.juliano.limpacel

import android.content.Context
import java.io.File
import java.util.Locale

/** Regras compartilhadas pela varredura, seleção e todas as operações de arquivos. */
object ScanPolicy {
    const val DEFAULT_LARGE_MB = 100
    const val TRASH_FOLDER = ".SmartCleanTrash"

    fun normalized(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    fun isInside(path: String, directory: String): Boolean {
        val candidate = normalized(path)
        val parent = normalized(directory)
        return candidate == parent || candidate.startsWith("$parent/")
    }

    fun systemProtected(path: String): Boolean {
        val normalizedPath = "/${normalized(path).trimStart('/')}"
        return listOf("/android/data", "/android/obb", "/$TRASH_FOLDER".lowercase(Locale.ROOT))
            .any { component -> normalizedPath == component || normalizedPath.endsWith(component) ||
                normalizedPath.contains("$component/") }
    }

    fun protected(path: String, excludedFolders: Set<String>): Boolean =
        systemProtected(path) || excludedFolders.any { isInside(path, it) }

    fun isHidden(path: String): Boolean = normalized(path).split('/').any { it.startsWith('.') && it.length > 1 }

    fun isThumbnail(path: String): Boolean =
        normalized(path).split('/').any { it == ".thumbnails" || it == "thumbnails" }

    fun isAppMedia(path: String): Boolean = normalized(path).contains("/android/media/")

    fun isMedia(kind: FileKind): Boolean = kind in setOf(
        FileKind.PHOTO, FileKind.SCREENSHOT, FileKind.VIDEO,
        FileKind.SCREEN_RECORDING, FileKind.MUSIC, FileKind.AUDIO
    )

    /** Somente arquivos com tamanho igual são candidatos à comparação de conteúdo. */
    fun sameSizeCandidates(files: List<MegaFile>): Map<Long, List<MegaFile>> =
        files.asSequence().filter { it.bytes > 0L }.groupBy { it.bytes }
            .filterValues { it.size > 1 }
}

class ScanSettings(context: Context) {
    private val prefs = context.getSharedPreferences("smart_clean_scan", Context.MODE_PRIVATE)

    var largeFileMb: Int
        get() = prefs.getInt("large_file_mb", ScanPolicy.DEFAULT_LARGE_MB)
        set(value) { prefs.edit().putInt("large_file_mb", value.coerceIn(25, 4096)).apply() }

    var deepHashing: Boolean
        get() = prefs.getBoolean("deep_hashing", false)
        set(value) { prefs.edit().putBoolean("deep_hashing", value).apply() }

    fun excludedFolders(): Set<String> = prefs.getStringSet("excluded_folders", emptySet())
        .orEmpty().toSet()

    fun protectFolder(path: String) {
        val folder = runCatching { File(path).canonicalPath }.getOrDefault(path)
        if (ScanPolicy.systemProtected(folder)) return
        prefs.edit().putStringSet("excluded_folders", excludedFolders() + folder).apply()
    }

    fun unprotectFolder(path: String) {
        prefs.edit().putStringSet("excluded_folders", excludedFolders() - path).apply()
    }
}
