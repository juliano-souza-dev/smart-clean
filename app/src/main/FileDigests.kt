package com.juliano.limpacel

import java.io.File
import java.security.MessageDigest

/** Hash calculado em blocos; não carrega o arquivo inteiro na memória. */
object FileDigests {
    fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                val size = stream.read(bytes)
                if (size < 0) break
                if (size > 0) digest.update(bytes, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Compara apenas arquivos de mesmo tamanho; mantém um grupo para cada hash confirmado. */
    fun findDuplicates(files: List<MegaFile>, digest: (MegaFile) -> String?): List<List<MegaFile>> =
        ScanPolicy.sameSizeCandidates(files).values.flatMap { candidates ->
            candidates.mapNotNull { file -> digest(file)?.let { it to file } }
                .groupBy({ it.first }, { it.second }).values.filter { it.size > 1 }
        }
}
