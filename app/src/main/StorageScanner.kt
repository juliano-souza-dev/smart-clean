package com.juliano.limpacel

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.ArrayDeque

data class DiskUsage(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

data class ScannedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val type: String
) {
    val id: String get() = uri.toString()
}

data class ScanReport(
    val files: List<ScannedFile>,
    val duplicateGroups: List<List<ScannedFile>>,
    val skippedEntries: Int,
    val wasTruncated: Boolean,
    val hashingLimited: Boolean
) {
    val largeFiles: List<ScannedFile> get() = files.filter { it.sizeBytes >= StorageScanner.LARGE_FILE_BYTES }
    val duplicateIds: Set<String> get() = duplicateGroups.flatten().mapTo(mutableSetOf()) { it.id }
    val knownBytes: Long get() = files.sumOf { it.sizeBytes }
}

data class DeleteReport(val removed: Int, val failed: Int)

/** Opera somente na árvore liberada pelo seletor do sistema. */
class StorageScanner(private val context: Context) {
    companion object {
        const val LARGE_FILE_BYTES = 100L * 1024L * 1024L
        private const val MAX_HASH_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_HASH_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_FILES = 10_000
        private const val MAX_DIRECTORIES = 3_000
        private const val MAX_DEPTH = 24
    }

    fun diskUsage(): DiskUsage {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        return DiskUsage(stats.totalBytes, stats.availableBytes)
    }

    fun scan(treeUri: Uri): ScanReport {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Pasta selecionada não está mais disponível")
        require(root.isDirectory) { "A seleção não é uma pasta acessível" }

        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.add(root to 0)
        val files = ArrayList<ScannedFile>()
        var directoryCount = 0
        var skipped = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (directoryCount++ >= MAX_DIRECTORIES) {
                truncated = true
                break
            }
            val (directory, depth) = queue.removeFirst()
            val children = try {
                directory.listFiles()
            } catch (_: Exception) {
                skipped++
                continue
            }

            for (child in children) {
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) queue.add(child to (depth + 1))
                    else truncated = true
                } else if (child.isFile) {
                    if (files.size >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    val size = runCatching { child.length() }.getOrNull()
                    if (size == null || size < 0) {
                        skipped++
                        continue
                    }
                    files += ScannedFile(
                        uri = child.uri,
                        name = child.name ?: "Arquivo sem nome",
                        sizeBytes = size,
                        type = child.type ?: "application/octet-stream"
                    )
                }
            }
            if (files.size >= MAX_FILES) {
                truncated = true
                break
            }
        }

        // Tamanho igual é apenas um filtro inicial; duplicidade exige hash.
        var totalHashed = 0L
        var hashingLimited = false
        val duplicateGroups = mutableListOf<List<ScannedFile>>()
        val groupsBySize = files.asSequence()
            .filter { it.sizeBytes > 0L }
            .groupBy { it.sizeBytes }
            .filterValues { it.size > 1 }

        for ((size, candidates) in groupsBySize) {
            if (size > MAX_HASH_FILE_BYTES) {
                hashingLimited = true
                continue
            }
            val byHash = linkedMapOf<String, MutableList<ScannedFile>>()
            for (file in candidates) {
                if (totalHashed + size > MAX_TOTAL_HASH_BYTES) {
                    hashingLimited = true
                    break
                }
                totalHashed += size
                val hash = sha256(file.uri)
                if (hash == null) {
                    skipped++
                    continue
                }
                byHash.getOrPut(hash) { mutableListOf() } += file
            }
            duplicateGroups += byHash.values.filter { it.size > 1 }
        }

        return ScanReport(
            files = files.sortedByDescending { it.sizeBytes },
            duplicateGroups = duplicateGroups,
            skippedEntries = skipped,
            wasTruncated = truncated,
            hashingLimited = hashingLimited
        )
    }

    fun deleteSelected(uris: List<Uri>): DeleteReport {
        var removed = 0
        var failed = 0
        uris.distinct().forEach { uri ->
            val deleted = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            if (deleted) removed++ else failed++
        }
        return DeleteReport(removed, failed)
    }

    private fun sha256(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            null
        }
    }
}

fun formatBytes(size: Long): String {
    if (size < 1024L) return "$size B"
    val units = listOf("KB", "MB", "GB", "TB")
    var number = size.toDouble()
    var unitIndex = -1
    do {
        number /= 1024.0
        unitIndex++
    } while (number >= 1024.0 && unitIndex < units.lastIndex)
    return "%.1f %s".format(java.util.Locale("pt", "BR"), number, units[unitIndex])
}
