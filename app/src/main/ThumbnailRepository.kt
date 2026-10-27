package com.juliano.limpacel

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Evita processar centenas de miniaturas simultaneamente enquanto o usuário percorre a lista. */
internal object ThumbnailRepository {
    private val limiter = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun load(file: MegaFile): Bitmap? {
        val kind = file.kind
        if (kind != FileKind.PHOTO && kind != FileKind.SCREENSHOT &&
            kind != FileKind.VIDEO && kind != FileKind.SCREEN_RECORDING) return null

        val key = "${file.path}|${file.modified}|${file.bytes}"
        cache.get(key)?.let { return it }

        return limiter.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(file.path)
                    if (!source.isFile || !snapshotMatches(source, file)) null
                    else if (kind == FileKind.PHOTO || kind == FileKind.SCREENSHOT)
                        ThumbnailUtils.createImageThumbnail(source, Size(96, 96), null)
                    else ThumbnailUtils.createVideoThumbnail(source, Size(96, 96), null)
                }.getOrNull()
            }
            if (result != null) cache.put(key, result)
            result
        }
    }
}
