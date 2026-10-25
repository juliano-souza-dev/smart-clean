package com.juliano.limpacel

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MegaScannerTest {
    private fun item(name: String, folder: String = "/storage/emulated/0/Download"): MegaFile =
        MegaFile("$folder/$name", name, 123L, 1000L)

    @Test fun classifiesPhotos() {
        assertEquals(FileKind.PHOTO, item("camera.JPG", "/storage/emulated/0/DCIM/Camera").kind)
        assertEquals(FileKind.PHOTO, item("portrait.heic").kind)
        assertEquals(FileKind.PHOTO, item("picture.avif").kind)
    }

    @Test fun separatesScreenshots() {
        assertEquals(FileKind.SCREENSHOT, item("Screenshot_2026.png").kind)
        assertEquals(FileKind.SCREENSHOT, item("capture.webp", "/Pictures/Screenshots").kind)
        assertEquals(FileKind.SCREENSHOT, item("captura.png").kind)
    }

    @Test fun separatesScreenRecordings() {
        assertEquals(FileKind.SCREEN_RECORDING, item("screen_record_01.mp4").kind)
        assertEquals(FileKind.SCREEN_RECORDING, item("clip.mkv", "/Movies/ScreenRecord").kind)
        assertEquals(FileKind.VIDEO, item("holiday.MP4").kind)
    }

    @Test fun distinguishesMusicFromOtherAudio() {
        assertEquals(FileKind.MUSIC, item("track.flac", "/storage/emulated/0/Music").kind)
        assertEquals(FileKind.AUDIO, item("voice.ogg", "/storage/emulated/0/Recordings").kind)
        assertEquals(FileKind.AUDIO, item("note.amr").kind)
    }

    @Test fun classifiesDocumentsArchivesAndApks() {
        assertEquals(FileKind.PDF, item("manual.PDF").kind)
        assertEquals(FileKind.DOCUMENT, item("report.docx").kind)
        assertEquals(FileKind.DOCUMENT, item("data.csv").kind)
        assertEquals(FileKind.ARCHIVE, item("backup.7z").kind)
        assertEquals(FileKind.ARCHIVE, item("photos.zip").kind)
        assertEquals(FileKind.APK, item("installer.APK").kind)
        assertEquals(FileKind.OTHER, item("unknown.xyz").kind)
    }

    @Test fun marksOnlyKnownTemporaryExtensions() {
        assertTrue(item("old.part").isTemporary)
        assertTrue(item("crash.LOG").isTemporary)
        assertTrue(item("download.crdownload").isTemporary)
        assertFalse(item("report.pdf").isTemporary)
    }

    @Test fun snapshotMatchesUnchangedFile() {
        val file = Files.createTempFile("smart-clean", ".txt").toFile()
        try {
            file.writeText("original")
            val snapshot = MegaFile(file.absolutePath, file.name, file.length(), file.lastModified())
            assertTrue(snapshotMatches(file, snapshot))
        } finally { file.delete() }
    }

    @Test fun snapshotRejectsChangedSize() {
        val file = Files.createTempFile("smart-clean", ".txt").toFile()
        try {
            file.writeText("original")
            val snapshot = MegaFile(file.absolutePath, file.name, file.length(), file.lastModified())
            file.appendText("-alterado")
            assertFalse(snapshotMatches(file, snapshot))
        } finally { file.delete() }
    }

    @Test fun snapshotRejectsChangedTimestamp() {
        val file = Files.createTempFile("smart-clean", ".txt").toFile()
        try {
            file.writeText("original")
            val snapshot = MegaFile(file.absolutePath, file.name, file.length(), file.lastModified())
            assertTrue(file.setLastModified(snapshot.modified + 5000L))
            assertFalse(snapshotMatches(file, snapshot))
        } finally { file.delete() }
    }

    @Test fun snapshotRejectsMissingFile() {
        val file = File("/this-path-should-not-exist/smart-clean-test-file.tmp")
        val snapshot = MegaFile(file.absolutePath, file.name, 0L, 0L)
        assertFalse(snapshotMatches(file, snapshot))
    }

    @Test fun snapshotRejectsDirectory() {
        val dir = Files.createTempDirectory("smart-clean").toFile()
        try {
            val snapshot = MegaFile(dir.absolutePath, dir.name, dir.length(), dir.lastModified())
            assertFalse(snapshotMatches(dir, snapshot))
        } finally { dir.delete() }
    }
}
