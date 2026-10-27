package com.juliano.limpacel

/** Índice montado uma vez fora da interface. Evita filtrar milhares de arquivos a cada toque. */
internal data class CategorySlice(val files: List<MegaFile>, val totalBytes: Long) {
    val count: Int get() = files.size
    fun page(limit: Int): List<MegaFile> = files.take(limit.coerceAtLeast(0))
}

internal class FileResultsCatalog private constructor(
    private val categories: Map<FileCategory, CategorySlice>
) {
    operator fun get(category: FileCategory): CategorySlice =
        categories[category] ?: CategorySlice(emptyList(), 0L)

    companion object {
        fun build(report: MegaReport, checkCancelled: () -> Unit = {}): FileResultsCatalog {
            val lists = FileCategory.entries.associateWith { ArrayList<MegaFile>() }
            val sizes = FileCategory.entries.associateWith { 0L }.toMutableMap()
            val duplicatePaths = report.duplicatePaths
            val outsidePaths = report.mediaOutsideIndex.asSequence().map { it.path }.toHashSet()
            val kindCategory = mapOf(
                FileKind.PHOTO to FileCategory.PHOTOS,
                FileKind.SCREENSHOT to FileCategory.CAPTURES,
                FileKind.VIDEO to FileCategory.VIDEOS,
                FileKind.SCREEN_RECORDING to FileCategory.SCREEN_RECORDS,
                FileKind.MUSIC to FileCategory.MUSIC,
                FileKind.AUDIO to FileCategory.AUDIOS,
                FileKind.PDF to FileCategory.PDF,
                FileKind.DOCUMENT to FileCategory.DOCUMENT,
                FileKind.ARCHIVE to FileCategory.ARCHIVES,
                FileKind.APK to FileCategory.APK,
                FileKind.OTHER to FileCategory.OTHER
            )

            fun add(category: FileCategory, file: MegaFile) {
                lists.getValue(category).add(file)
                sizes[category] = sizes.getValue(category) + file.bytes
            }

            report.files.forEachIndexed { index, file ->
                if (index % 512 == 0) checkCancelled()
                add(FileCategory.ALL, file)
                if (file.bytes >= report.largeThresholdBytes) add(FileCategory.LARGE, file)
                if (file.path in duplicatePaths) add(FileCategory.DUPLICATES, file)
                if (file.isTemporary) add(FileCategory.TEMPORARY, file)
                if (file.isHidden) add(FileCategory.HIDDEN, file)
                if (file.isThumbnail) add(FileCategory.THUMBNAILS, file)
                if (file.isAppMedia) add(FileCategory.APP_MEDIA, file)
                if (file.path in outsidePaths) add(FileCategory.OUTSIDE_INDEX, file)
                kindCategory[file.kind]?.let { add(it, file) }
            }
            return FileResultsCatalog(lists.mapValues { (category, files) ->
                CategorySlice(files, sizes.getValue(category))
            })
        }
    }
}
