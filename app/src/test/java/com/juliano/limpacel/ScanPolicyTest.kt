package com.juliano.limpacel

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScanPolicyTest {
    private fun sample(name: String, bytes: Long = 100, directory: String = "/storage/emulated/0/Download") =
        MegaFile("$directory/$name", name, bytes, 1000)

    @Test fun protectsPrivateAndroidFoldersButAllowsAppMedia() {
        assertTrue(ScanPolicy.systemProtected("/storage/emulated/0/Android/data/com.example/cache/a"))
        assertTrue(ScanPolicy.systemProtected("/storage/emulated/0/Android/obb/com.example/a"))
        assertTrue(ScanPolicy.systemProtected("/storage/emulated/0/.SmartCleanTrash/uuid"))
        assertFalse(ScanPolicy.systemProtected("/storage/emulated/0/Android/media/com.example/video.mp4"))
    }

    @Test fun userProtectedFolderIncludesChildrenOnly() {
        val excluded = setOf("/storage/emulated/0/Documents/Trabalho")
        assertTrue(ScanPolicy.protected("/storage/emulated/0/Documents/Trabalho/doc.pdf", excluded))
        assertFalse(ScanPolicy.protected("/storage/emulated/0/Documents/Trabalho2/doc.pdf", excluded))
    }

    @Test fun recognizesHiddenAndThumbnailMedia() {
        assertTrue(sample(".nomedia").isHidden)
        assertTrue(sample("small.jpg", directory = "/storage/emulated/0/DCIM/.thumbnails").isThumbnail)
        assertTrue(sample("clip.mp4", directory = "/storage/emulated/0/Android/media/com.chat").isAppMedia)
        assertFalse(sample("clip.mp4").isHidden)
    }

    @Test fun candidatesAreGroupedOnlyByEqualSize() {
        val list = listOf(sample("a.mp4", 100), sample("b.mp4", 100),
            sample("c.mp4", 101), sample("d.mp4", 0))
        val groups = ScanPolicy.sameSizeCandidates(list)
        assertEquals(1, groups.size)
        assertEquals(list.take(2), groups[100])
    }

    @Test fun hashFindsIdenticalFilesAndPreservesDifferentOnes() {
        val directory = Files.createTempDirectory("smart-clean-hashes")
        try {
            val first = directory.resolve("first.txt").toFile().apply { writeText("12345678") }
            val second = directory.resolve("second.txt").toFile().apply { writeText("12345678") }
            val third = directory.resolve("third.txt").toFile().apply { writeText("87654321") }
            val fourth = directory.resolve("fourth.txt").toFile().apply { writeText("longer text") }
            val snapshots = listOf(first, second, third, fourth).map {
                MegaFile(it.absolutePath, it.name, it.length(), it.lastModified())
            }
            val attempted = mutableListOf<String>()
            val groups = FileDigests.findDuplicates(snapshots) { file ->
                attempted += file.path
                FileDigests.sha256(java.io.File(file.path))
            }
            assertEquals(1, groups.size)
            assertEquals(setOf(first.absolutePath, second.absolutePath),
                groups.single().map { it.path }.toSet())
            assertFalse(fourth.absolutePath in attempted)
        } finally { directory.toFile().listFiles()?.forEach { it.delete() }; directory.toFile().delete() }
    }

    @Test fun classificationKeepsAllVisibleMediaCategories() {
        assertEquals(FileKind.APK, sample("old.apk").kind)
        assertEquals(FileKind.PDF, sample("manual.pdf").kind)
        assertTrue(sample("partial.crdownload").isTemporary)
        assertEquals(FileKind.SCREENSHOT, sample("Screenshot_2026.png").kind)
    }
}
