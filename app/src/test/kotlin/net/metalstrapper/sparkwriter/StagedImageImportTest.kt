package net.metalstrapper.sparkwriter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class StagedImageImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val image = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
    private val imageSha256 = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }

    @Test
    fun stagesAContentAddressedCopyWhileHashing() {
        val dir = temp.newFolder("imports")
        val read = readAndStageImage(ByteArrayInputStream(image), dir)

        assertEquals(imageSha256, read.sha256)
        assertEquals(image.size.toLong(), read.sizeBytes)
        assertEquals("$imageSha256.iso", read.stagedFile!!.name)
        assertArrayEquals(image, read.stagedFile!!.readBytes())
        assertEquals(listOf(read.stagedFile!!.name), dir.list()!!.toList())
    }

    @Test
    fun reimportingTheSameImageReusesTheStagedCopy() {
        val dir = temp.newFolder("imports")
        val first = readAndStageImage(ByteArrayInputStream(image), dir)
        val second = readAndStageImage(ByteArrayInputStream(image), dir)

        assertEquals(first.stagedFile, second.stagedFile)
        assertEquals(1, dir.list()!!.size)
    }

    @Test
    fun hashesWithoutCopyingWhenNoStagingDirectoryIsGiven() {
        val read = readAndStageImage(ByteArrayInputStream(image), stagingDir = null)

        assertEquals(imageSha256, read.sha256)
        assertNull(read.stagedFile)
    }

    @Test
    fun failedReadLeavesNoPartialCopy() {
        val dir = temp.newFolder("imports")
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("provider went away")
                served += len
                return len
            }
        }

        assertThrows(IOException::class.java) { readAndStageImage(failing, dir) }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun emptyImageIsRefusedAndNotStaged() {
        val dir = temp.newFolder("imports")

        assertThrows(IllegalStateException::class.java) { readAndStageImage(ByteArrayInputStream(ByteArray(0)), dir) }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun pruneKeepsOnlyTheNewestImportAndRemovesPartials() {
        val dir = temp.newFolder("imports")
        val keep = readAndStageImage(ByteArrayInputStream(image), dir).stagedFile!!
        val older = dir.resolve("${"b".repeat(64)}.iso").apply { writeText("old") }
        val partial = dir.resolve("import-123.partial").apply { writeText("partial") }
        val unrelated = dir.resolve("notes.txt").apply { writeText("keep") }

        pruneStagedImports(dir, keep)

        assertTrue(keep.isFile)
        assertTrue(unrelated.isFile)
        assertTrue(!older.exists() && !partial.exists())
    }
}
