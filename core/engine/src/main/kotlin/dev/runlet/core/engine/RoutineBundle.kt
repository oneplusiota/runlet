package dev.runlet.core.engine

import dev.runlet.core.model.Limits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One routine travelling in or out of the app. [name] is a human label, not a path. */
data class RoutineFile(val name: String, val source: String)

/** What a file or archive held. [skipped] counts entries that were not readable text. */
data class BundleContents(val files: List<RoutineFile>, val skipped: Int)

/**
 * Reading and writing routine files. Routines travel as plain UTF-8 `.routine` text; a backup is a `.zip` of them.
 *
 * Everything here treats its input as untrusted: files come from other apps, chats and downloads, so sizes are
 * bounded while reading (not after), archives are never extracted to disk, and nothing is ever run.
 */
object RoutineBundle {
    const val EXTENSION = "routine"

    /** 100,000 characters of up to 4 UTF-8 bytes each. */
    const val MAX_FILE_BYTES = Limits.MAX_SOURCE_CHARS * 4
    const val MAX_ENTRIES = 200
    const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024

    private val RESERVED = "\\/:*?\"<>|"
    private val ZIP_LOCAL = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)   // starts a non-empty archive
    private val ZIP_EMPTY = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6)   // an archive with no entries

    /** A safe file name such as `French press.routine`; reserved characters become `_`. */
    fun fileNameFor(name: String): String {
        val cleaned = name.map { if (it in RESERVED || it.isISOControl()) '_' else it }
            .joinToString("").trim().trim('.').take(60).trim()
        return cleaned.ifEmpty { "routine" } + "." + EXTENSION
    }

    /** Decodes routine text strictly. Throws [IOException] with a message the user can act on. */
    fun decode(bytes: ByteArray): String {
        val text = try {
            when {
                bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> {
                    val skip = if (bytes.startsWith(0xEF, 0xBB, 0xBF)) 3 else 0
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
                        .toString()
                }
            }
        } catch (_: CharacterCodingException) {
            throw IOException("That is not a text file")
        }
        if (text.any { it == '\u0000' }) throw IOException("That is not a text file")
        if (text.length > Limits.MAX_SOURCE_CHARS) throw IOException("A routine can be at most 100,000 characters")
        return text
    }

    /** Reads a single `.routine` text or a `.zip` of them, sniffing which by content rather than by name. */
    fun read(input: InputStream): BundleContents {
        val stream = PushbackInputStream(input, ZIP_LOCAL.size)
        val head = ByteArray(ZIP_LOCAL.size)
        var got = 0
        while (got < head.size) {
            val n = stream.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        if (got > 0) stream.unread(head, 0, got)
        return if (got == head.size && (head.contentEquals(ZIP_LOCAL) || head.contentEquals(ZIP_EMPTY))) readZip(stream)
        else BundleContents(listOf(RoutineFile("routine", decode(readBounded(stream, MAX_FILE_BYTES)))), 0)
    }

    private fun readZip(input: InputStream): BundleContents {
        val files = ArrayList<RoutineFile>()
        var skipped = 0
        var entries = 0
        var total = 0L
        val counted = CountingInput(input)
        ZipInputStream(counted).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (_: java.util.zip.ZipException) {
                    throw IOException("The archive is damaged")
                } ?: break
                val base = entry.name.substringAfterLast('/')
                // Directories and OS metadata (macOS resource forks, dotfiles) are not routines and not worth reporting.
                if (entry.isDirectory || entry.name.startsWith("__MACOSX/") || base.startsWith(".")) continue
                if (++entries > MAX_ENTRIES) throw IOException("The archive holds more than $MAX_ENTRIES files")
                // Bounded while reading so a decompression bomb is stopped, not merely rejected afterwards.
                val bytes = try {
                    readBounded(zip, MAX_FILE_BYTES)
                } catch (e: java.util.zip.ZipException) {
                    throw IOException("The archive is damaged")
                } catch (e: java.io.EOFException) {
                    throw IOException("The archive is damaged")
                } catch (_: IOException) {
                    throw IOException("The archive contains a file that is too large to be a routine")
                }
                total += bytes.size
                if (total > MAX_ARCHIVE_BYTES) throw IOException("The archive is too large")
                try {
                    files += RoutineFile(base.substringBeforeLast('.'), decode(bytes))
                } catch (_: IOException) {
                    skipped++
                }
            }
        }
        // A complete archive ends with a central directory ("PK\u0001\u0002" entries, then "PK\u0005\u0006").
        // ZipInputStream reads local entries only and stops silently at a cut-off, so require the end record.
        if (!counted.sawEndRecord()) throw IOException("The archive is damaged or incomplete")
        return BundleContents(files, skipped)
    }

    /** Passes bytes through while remembering the last few, so the zip end-of-archive marker can be checked. */
    private class CountingInput(private val inner: InputStream) : InputStream() {
        private val tail = ByteArray(64 * 1024)
        private var filled = 0
        private var pos = 0

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) remember(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(buf, off, len)
            if (n > 0) remember(buf, off, n)
            return n
        }

        /** Drains whatever ZipInputStream left unread (the central directory) so the end record is seen. */
        fun sawEndRecord(): Boolean {
            val buf = ByteArray(8192)
            while (true) { val n = inner.read(buf); if (n < 0) break; remember(buf, 0, n) }
            val ordered = ByteArray(filled)
            for (i in 0 until filled) ordered[i] = tail[(pos - filled + i + tail.size * 2) % tail.size]
            // The end record is at least 22 bytes and sits within the last 64 KB (max zip comment length).
            for (i in ordered.size - 22 downTo 0) {
                if (ordered[i] == 'P'.code.toByte() && ordered[i + 1] == 'K'.code.toByte() &&
                    ordered[i + 2] == 5.toByte() && ordered[i + 3] == 6.toByte()) return true
            }
            return false
        }

        private fun remember(b: ByteArray, off: Int, len: Int) {
            for (i in 0 until len) { tail[pos] = b[off + i]; pos = (pos + 1) % tail.size }
            filled = minOf(tail.size, filled + len)
        }
    }

    /** Zips [files] for a backup. Names are made unique (`A.routine`, `A (2).routine`) so none overwrite another. */
    fun writeZip(files: List<RoutineFile>): ByteArray {
        val out = ByteArrayOutputStream()
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for (f in files) {
                val base = fileNameFor(f.name).removeSuffix(".$EXTENSION")
                var candidate = "$base.$EXTENSION"
                var n = 2
                while (!used.add(candidate.lowercase())) candidate = "$base (${n++}).$EXTENSION"
                zip.putNextEntry(ZipEntry(candidate))
                zip.write(f.source.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * True when pasted or shared [text] is plausibly a routine rather than any random text: it names a routine,
     * or it parses as a script. Used only for text; files are always accepted as drafts.
     */
    fun looksLikeRoutine(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length > Limits.MAX_SOURCE_CHARS) return false
        if (ScriptText.routineName(text) != null) return true
        val hasCode = text.lineSequence().any { it.isNotBlank() && !it.trim().startsWith("#") }
        return hasCode && runCatching { ScriptParser.parseOutline(text) }.isSuccess
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("That file is too large to be a routine")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
