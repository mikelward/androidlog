package app.mikelward.androidlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy floor, asserted in both directions: what is withheld off device,
 * and — just as much — what is *kept*, since a floor that quietly widened to
 * withhold everything would pass a one-sided test while leaving the consumers
 * unable to explain their own failures (AGENTS.md, *Testing*).
 */
class LogValueTest {

    private enum class Mode { ARMED, IDLE }

    /**
     * An enum whose `toString()` reads live state, which is the shape that used
     * to walk through the floor: the type rule lets an enum past on the
     * strength of its identity being fixed vocabulary, and then rendering
     * called the overridable method instead.
     */
    private enum class Leaky {
        CURRENT;

        override fun toString(): String = "ExampleWifi"
    }

    private fun full(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = false)

    private fun mirrored(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = true)

    @Test
    fun `a String is withheld off device but kept on it`() {
        assertEquals("joined ExampleWifi", full("joined %s", "ExampleWifi"))
        assertEquals("joined $REDACTED_PLACEHOLDER", mirrored("joined %s", "ExampleWifi"))
    }

    @Test
    fun `numbers booleans enums and null are carried`() {
        assertTrue(logArgumentMayLeaveDevice(42))
        assertTrue(logArgumentMayLeaveDevice(42L))
        assertTrue(logArgumentMayLeaveDevice(4.2))
        assertTrue(logArgumentMayLeaveDevice(4.2f))
        assertTrue(logArgumentMayLeaveDevice(true))
        assertTrue(logArgumentMayLeaveDevice('x'))
        assertTrue(logArgumentMayLeaveDevice(Mode.ARMED))
        assertTrue(logArgumentMayLeaveDevice(null))
        assertEquals(
            "mode=ARMED distance=120m ok=true",
            mirrored("mode=%s distance=%sm ok=%s", Mode.ARMED, 120, true),
        )
    }

    @Test
    fun `a String is withheld and so is an arbitrary object`() {
        assertFalse(logArgumentMayLeaveDevice("anything"))
        assertFalse(logArgumentMayLeaveDevice(Any()))
    }

    @Test
    fun `safe carries a String the type rule would withhold`() {
        assertTrue(logArgumentMayLeaveDevice(safe("android.intent.action.MAIN")))
        assertEquals(
            "action=android.intent.action.MAIN",
            mirrored("action=%s", safe("android.intent.action.MAIN")),
        )
    }

    @Test
    fun `sensitive withholds a number the type rule would carry`() {
        assertFalse(logArgumentMayLeaveDevice(sensitive(51.5)))
        assertEquals("lat=51.5", full("lat=%s", sensitive(51.5)))
        assertEquals("lat=$REDACTED_PLACEHOLDER", mirrored("lat=%s", sensitive(51.5)))
    }

    @Test
    fun `a summary renders full on device and reduced off it`() {
        val summary = LogSummary(full = "snooze(at=Home, 3h)", mirrored = "snooze(3h)")
        assertEquals("state=snooze(at=Home, 3h)", full("state=%s", summary))
        assertEquals("state=snooze(3h)", mirrored("state=%s", summary))
    }

    @Test
    fun `a double percent renders one literal percent`() {
        assertEquals("battery 80%", full("battery 80%%"))
    }

    @Test
    fun `a surplus placeholder is left in place rather than dropping the line`() {
        assertEquals("a=1 b=%s", full("a=%s b=%s", 1))
    }

    @Test
    fun `a surplus argument is appended and redacted like a placed one`() {
        assertEquals("a=1 [unplaced arg] secret", full("a=%s", 1, "secret"))
        assertEquals("a=1 [unplaced arg] $REDACTED_PLACEHOLDER", mirrored("a=%s", 1, "secret"))
    }

    @Test
    fun `a format with no arguments and no percent is returned unchanged`() {
        assertEquals("plain line", full("plain line"))
    }

    @Test
    fun `a trailing percent is left alone rather than throwing`() {
        assertEquals("done 100%", full("done 100%"))
    }

    @Test
    fun `an untagged enum renders its constant name, not an overridden toString`() {
        assertTrue(logArgumentMayLeaveDevice(Leaky.CURRENT))
        assertEquals("mode=CURRENT", full("mode=%s", Leaky.CURRENT))
        assertEquals("mode=CURRENT", mirrored("mode=%s", Leaky.CURRENT))
    }

    @Test
    fun `a plain enum still renders its name`() {
        assertEquals("mode=ARMED", full("mode=%s", Mode.ARMED))
    }

    @Test
    fun `safe honors toString, because the call site asked for that value`() {
        assertEquals("mode=ExampleWifi", full("mode=%s", safe(Leaky.CURRENT)))
        assertEquals("mode=ExampleWifi", mirrored("mode=%s", safe(Leaky.CURRENT)))
    }

    @Test
    fun `a tag wrapped around a tag does not let a throwable message through`() {
        // The wrappers are value classes, so their generated `toString()`
        // prints their contents: reaching the untagged branch, the outer one
        // rendered the exception through `toString()` and carried the message
        // the floor exists to exclude.
        val nested = safe(safe(IllegalStateException("dialed +15550100")))
        assertEquals("gave up on java.lang.IllegalStateException", full("gave up on %s", nested))
        assertFalse(full("gave up on %s", nested).contains("+15550100"))
    }

    @Test
    fun `sensitive wins however many safe wrappers are around it`() {
        assertFalse(logArgumentMayLeaveDevice(safe(sensitive("ExampleWifi"))))
        assertFalse(logArgumentMayLeaveDevice(safe(safe(sensitive(1.0)))))
        assertEquals("at $REDACTED_PLACEHOLDER", mirrored("at %s", safe(sensitive("ExampleWifi"))))
        // On device it is still rendered in full -- withholding is about what
        // leaves, not about what the user reads before sharing.
        assertEquals("at ExampleWifi", full("at %s", safe(sensitive("ExampleWifi"))))
    }

    @Test
    fun `a summary keeps its own mirrored form through a tag`() {
        val summary = LogSummary(full = "at Home, 12m", mirrored = "at a place, 12m")
        assertEquals("snooze at Home, 12m", full("snooze %s", safe(summary)))
        assertEquals("snooze at a place, 12m", mirrored("snooze %s", safe(summary)))
    }

    @Test
    fun `a plain safe wrapper still vouches for its value`() {
        // The other direction: collapsing the tags must not cost `safe` its
        // whole purpose.
        assertTrue(logArgumentMayLeaveDevice(safe("ExampleWifi")))
        assertEquals("at ExampleWifi", mirrored("at %s", safe("ExampleWifi")))
    }
}
