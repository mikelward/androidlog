package app.mikelward.androidlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTailTest {

    @Test
    fun `the tail keeps the newest lines that fit`() {
        val kept = boundedLogTail(listOf("aaaa", "bbbb", "cccc"), budgetChars = 10)
        assertEquals(listOf("bbbb", "cccc"), kept)
    }

    @Test
    fun `a single line over the budget is clamped rather than dropped`() {
        // Dropping it would lose the freshest context entirely; keeping it whole
        // would blow the ceiling this exists to enforce.
        val kept = boundedLogTail(listOf("x".repeat(100)), budgetChars = 20)
        assertEquals(1, kept.size)
        assertTrue(kept.single(), kept.single().endsWith("…(truncated)"))
        assertTrue(kept.single(), kept.single().length <= 20)
    }

    @Test
    fun `a budget too small for the marker itself keeps nothing`() {
        // Below the marker's own length there is no clamped form that fits, so
        // emitting one would overrun the ceiling the clamp exists to enforce.
        // Kept in step with `DebugLog`'s trim deliberately.
        for (budget in 0..11) {
            assertEquals(
                "budget $budget",
                emptyList<String>(),
                boundedLogTail(listOf("x".repeat(100)), budget),
            )
        }
    }

    @Test
    fun `a budget that fits exactly the marker keeps just the marker`() {
        val kept = boundedLogTail(listOf("x".repeat(100)), budgetChars = "…(truncated)".length)
        assertEquals(listOf("…(truncated)"), kept)
    }

    @Test
    fun `a clamped line never strands half a code point`() {
        // Kept in step with `DebugLog`'s clamp and the entry-length one: all
        // three cut through the same helper, because two of them counting
        // UTF-16 units while the third counted code points is how a shared rule
        // drifts apart.
        val line = "😀".repeat(20)
        for (budget in 14..40) {
            val text = boundedLogTail(listOf(line), budget).single()
            text.forEachIndexed { i, c ->
                if (c.isHighSurrogate()) {
                    assertTrue(
                        "budget $budget: unpaired high surrogate at $i",
                        i + 1 < text.length && text[i + 1].isLowSurrogate(),
                    )
                }
                if (c.isLowSurrogate()) {
                    assertTrue(
                        "budget $budget: unpaired low surrogate at $i",
                        i > 0 && text[i - 1].isHighSurrogate(),
                    )
                }
            }
        }
    }

    @Test
    fun `an empty log has an empty tail`() {
        assertEquals(emptyList<String>(), boundedLogTail(emptyList(), budgetChars = 100))
    }
}
