package app.mikelward.androidlog.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the chunker rather than the sink: `android.util.Log` is not callable
 * from a JVM unit test, and the arithmetic is where the bug was.
 */
class LogcatSinkTest {

    private fun String.utf8Bytes() = toByteArray(Charsets.UTF_8).size

    /**
     * What the native logger actually receives: each surrogate encoded on its
     * own at three bytes, and `NUL` in its two-byte form.
     */
    private fun String.modifiedUtf8Bytes() = sumOf { c ->
        when {
            c.code == 0 -> 2
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
    }

    @Test
    fun `a short line is returned whole`() {
        assertEquals(listOf("short"), "short".chunkedByModifiedUtf8Bytes(3_000))
    }

    @Test
    fun `an ascii line longer than the budget is split into pieces within it`() {
        val chunks = "a".repeat(250).chunkedByModifiedUtf8Bytes(100)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.utf8Bytes() <= 100 })
        assertEquals("a".repeat(250), chunks.joinToString(""))
    }

    @Test
    fun `multi-byte text is measured in bytes, not code units`() {
        // 60 units, 180 UTF-8 bytes: under a length-based budget of 100 and
        // well over a byte-based one, which is the case that used to slip
        // through unsplit and lose its tail in logcat.
        val text = "経".repeat(60)
        assertTrue(text.length <= 100)
        assertTrue(text.utf8Bytes() > 100)
        val chunks = text.chunkedByModifiedUtf8Bytes(100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.utf8Bytes() <= 100 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `supplementary characters are measured as JNI will encode them`() {
        // Six modified-UTF-8 bytes each, not the four standard UTF-8 spends:
        // JNI encodes each surrogate separately on the way to the native
        // logger. Sized the standard way these fit one chunk and then arrived
        // half again as long, so logcat could still drop the tail.
        val text = "\uD834\uDD1E".repeat(10)
        assertEquals(40, text.utf8Bytes())
        assertEquals(60, text.modifiedUtf8Bytes())
        val chunks = text.chunkedByModifiedUtf8Bytes(48)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.modifiedUtf8Bytes() <= 48 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `a surrogate pair is never cut in half`() {
        // A budget that is not a multiple of a whole character forces a split
        // mid-run.
        val text = "𝄞".repeat(40)
        val chunks = text.chunkedByModifiedUtf8Bytes(30)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.modifiedUtf8Bytes() <= 30 })
        // A halved pair would render as replacement characters on the round
        // trip; joining has to give back exactly what went in.
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { chunk -> chunk.none { it.isSurrogate() && chunk.length % 2 != 0 } })
    }

    @Test
    fun `a single code point larger than the budget is still emitted`() {
        val chunks = "𝄞".chunkedByModifiedUtf8Bytes(2)
        assertEquals(listOf("𝄞"), chunks)
    }
}
