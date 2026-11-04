package dev.runlet.core.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineBundleTest {
    private fun read(bytes: ByteArray) = RoutineBundle.read(ByteArrayInputStream(bytes))

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // --- file names --------------------------------------------------------------------------------

    @Test fun fileNamesAreSafeAndKeepReadableNames() {
        assertEquals("French press.routine", RoutineBundle.fileNameFor("French press"))
        assertEquals("a_b_c_.routine", RoutineBundle.fileNameFor("a/b\\c:"))
        assertEquals("routine.routine", RoutineBundle.fileNameFor("  ..  "))
        assertEquals("Café ☕.routine", RoutineBundle.fileNameFor("Café ☕"))
        assertEquals(60 + ".routine".length, RoutineBundle.fileNameFor("x".repeat(200)).length)
        assertFalse(RoutineBundle.fileNameFor("..\\..\\etc/passwd").contains('/'))
    }

    // --- decoding ----------------------------------------------------------------------------------

    @Test fun plainUtf8IsReadAsIs() {
        val text = "routine \"Café\"\nchime\n"
        assertEquals(text, RoutineBundle.decode(text.toByteArray()))
    }

    @Test fun aByteOrderMarkIsStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "chime".toByteArray()
        assertEquals("chime", RoutineBundle.decode(bytes))
    }

    @Test fun utf16FromOlderEditorsIsAccepted() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "chime".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "chime".toByteArray(Charsets.UTF_16BE)
        assertEquals("chime", RoutineBundle.decode(le))
        assertEquals("chime", RoutineBundle.decode(be))
    }

    @Test fun binaryAndInvalidTextAreRejected() {
        assertFailsWith<IOException> { RoutineBundle.decode(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1)) }
        assertFailsWith<IOException> { RoutineBundle.decode(byteArrayOf(0xC3.toByte(), 0x28)) }       // malformed UTF-8
        assertFailsWith<IOException> { RoutineBundle.decode("a\u0000b".toByteArray()) }
    }

    @Test fun overlongTextIsRejectedWithAClearMessage() {
        val e = assertFailsWith<IOException> { RoutineBundle.decode("x".repeat(100_001).toByteArray()) }
        assertEquals("A routine can be at most 100,000 characters", e.message)
    }

    // --- reading -----------------------------------------------------------------------------------

    @Test fun aSingleTextFileIsOneRoutine() {
        val r = read("routine \"A\"\nchime".toByteArray())
        assertEquals(listOf("routine \"A\"\nchime"), r.files.map { it.source })
        assertEquals(0, r.skipped)
    }

    @Test fun anEmptyFileIsAnEmptyRoutineDraftNotACrash() {
        assertEquals("", read(ByteArray(0)).files.single().source)
    }

    @Test fun zipsAreRecognisedByContentNotName() {
        val r = read(zip("a.routine" to "chime".toByteArray(), "b.txt" to "wait 1s".toByteArray()))
        assertEquals(listOf("a", "b"), r.files.map { it.name })
        assertEquals(listOf("chime", "wait 1s"), r.files.map { it.source })
    }

    @Test fun zipMetadataAndDirectoriesAreIgnoredButBinaryFilesAreCountedAsSkipped() {
        val r = read(
            zip(
                "folder/" to ByteArray(0),
                "__MACOSX/._a.routine" to byteArrayOf(0, 1, 2),
                ".DS_Store" to byteArrayOf(0, 1),
                "a.routine" to "chime".toByteArray(),
                "pic.png" to byteArrayOf(0x89.toByte(), 0, 0, 1),
            ),
        )
        assertEquals(listOf("a"), r.files.map { it.name })
        assertEquals(1, r.skipped)
    }

    @Test fun anOversizedFileIsRejectedWhileReading() {
        val big = ByteArray(RoutineBundle.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
        assertFailsWith<IOException> { read(big) }
    }

    @Test fun aDecompressionBombIsStopped() {
        // 3 MB of zeros compresses to a few KB; one entry above the per-file cap must abort the whole archive.
        val bomb = zip("big.routine" to ByteArray(3 * 1024 * 1024))
        assertTrue(bomb.size < 50_000)
        val e = assertFailsWith<IOException> { read(bomb) }
        assertEquals("The archive contains a file that is too large to be a routine", e.message)
    }

    @Test fun tooManyEntriesAreRejected() {
        val many = zip(*Array(RoutineBundle.MAX_ENTRIES + 1) { "r$it.routine" to "chime".toByteArray() })
        assertFailsWith<IOException> { read(many) }
    }

    @Test fun aTruncatedZipFailsCleanlyInsteadOfReturningHalfTheRoutines() {
        val good = zip("a.routine" to "chime".toByteArray(), "b.routine" to "wait 1s".toByteArray())
        assertFailsWith<IOException> { read(good.copyOf(good.size - 30)) }
    }

    // --- writing -----------------------------------------------------------------------------------

    @Test fun backupsRoundTripAndKeepEveryRoutine() {
        val files = listOf(RoutineFile("French press", ScriptParser.COFFEE), RoutineFile("Quick test", ScriptParser.DEMO))
        val back = read(RoutineBundle.writeZip(files))
        assertEquals(files.map { it.source }, back.files.map { it.source })
        assertEquals(listOf("French press", "Quick test"), back.files.map { it.name })
    }

    @Test fun backupNamesNeverCollide() {
        val bytes = RoutineBundle.writeZip(listOf(RoutineFile("A", "x"), RoutineFile("a", "y"), RoutineFile("A", "z"), RoutineFile("a/b", "w")))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals(listOf("A.routine", "a (2).routine", "A (3).routine", "a_b.routine"), names)
    }

    @Test fun anEmptyBackupIsStillAValidArchive() {
        assertContentEquals(emptyList(), read(RoutineBundle.writeZip(emptyList())).files)
    }

    @Test fun multibyteTextSurvivesTheRoundTrip() {
        val src = "routine \"Café ☕\"\nnotify \"こんにちは\"\n"
        assertEquals(src, read(RoutineBundle.writeZip(listOf(RoutineFile("Café ☕", src)))).files.single().source)
    }

    // --- recognising pasted text -------------------------------------------------------------------

    @Test fun routineTextIsRecognisedAndOrdinaryTextIsNot() {
        assertTrue(RoutineBundle.looksLikeRoutine(ScriptParser.COFFEE))
        assertTrue(RoutineBundle.looksLikeRoutine("chime\nwait 3s"))                       // parses, no routine line
        assertTrue(RoutineBundle.looksLikeRoutine("routine \"Draft\"\nstage \"Oops\""))     // named but unfinished
        assertFalse(RoutineBundle.looksLikeRoutine("Hello, are we still meeting at 5?"))
        assertFalse(RoutineBundle.looksLikeRoutine("# only a comment"))
        assertFalse(RoutineBundle.looksLikeRoutine("   "))
        assertFalse(RoutineBundle.looksLikeRoutine(null))
        assertFalse(RoutineBundle.looksLikeRoutine("chime\n".repeat(30_000)))               // over the length limit
    }
}
