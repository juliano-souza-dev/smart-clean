package com.juliano.limpacel

import org.junit.Assert.*
import org.junit.Test

class FileResultsCatalogTest {
    @Test fun indexesLargeScanOnceAndPaginatesWithoutChangingCounts() {
        val files = (1..12_500).map { i ->
            val name = if (i % 2 == 0) "video-$i.mp4" else "picture-$i.jpg"
            MegaFile("/storage/emulated/0/Download/$name", name, i * 100L, 1234L)
        }
        val report = MegaReport(files, emptyList(), 0, false, false,
            listOf("/storage/emulated/0"), largeThresholdBytes = 100_000L)
        val index = FileResultsCatalog.build(report)
        assertEquals(12_500, index[FileCategory.ALL].count)
        assertEquals(6_250, index[FileCategory.VIDEOS].count)
        assertEquals(6_250, index[FileCategory.PHOTOS].count)
        assertEquals(files.sumOf { it.bytes }, index[FileCategory.ALL].totalBytes)
        assertEquals(80, index[FileCategory.ALL].page(80).size)
        assertEquals(160, index[FileCategory.ALL].page(160).size)
        assertEquals(12_500, index[FileCategory.ALL].count)
    }

    @Test fun respectsOverlappingCategoriesWithoutDuplicatingAllFiles() {
        val file = MegaFile("/storage/emulated/0/Android/media/chat/.thumbnails/example.mp4",
            "example.mp4", 2000, 1000)
        val report = MegaReport(listOf(file), emptyList(), 0, false, false,
            listOf("/storage/emulated/0"), largeThresholdBytes = 1000,
            mediaOutsideIndex = listOf(file), mediaIndexChecked = true)
        val index = FileResultsCatalog.build(report)
        assertEquals(1, index[FileCategory.ALL].count)
        assertEquals(1, index[FileCategory.HIDDEN].count)
        assertEquals(1, index[FileCategory.THUMBNAILS].count)
        assertEquals(1, index[FileCategory.APP_MEDIA].count)
        assertEquals(1, index[FileCategory.OUTSIDE_INDEX].count)
        assertEquals(1, index[FileCategory.LARGE].count)
    }
}
