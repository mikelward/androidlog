package com.mikelward.androidlog

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

    /** Counts how many entries a rendering actually reads. */
    private class CountingMap(private val delegate: Map<String, Int>) : Map<String, Int> by delegate {
        var read = 0

        override val entries: Set<Map.Entry<String, Int>>
            get() = object : Set<Map.Entry<String, Int>> by delegate.entries {
                override fun iterator(): Iterator<Map.Entry<String, Int>> {
                    val inner = delegate.entries.iterator()
                    return object : Iterator<Map.Entry<String, Int>> {
                        override fun hasNext(): Boolean = inner.hasNext()

                        override fun next(): Map.Entry<String, Int> {
                            read++
                            return inner.next()
                        }
                    }
                }
            }
    }

    /** A domain type whose `toString()` this library did not write. */
    private class Holder {
        override fun toString(): String = "ssid ExampleWifi"
    }

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
        formatLogMessage(format, args, leavingDevice = false)

    private fun offDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, leavingDevice = true)

    @Test
    fun `a String is withheld off device but kept on it`() {
        assertEquals("joined ExampleWifi", full("joined %s", "ExampleWifi"))
        assertEquals("joined $OFF_DEVICE_PLACEHOLDER", offDevice("joined %s", "ExampleWifi"))
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
            offDevice("mode=%s distance=%sm ok=%s", Mode.ARMED, 120, true),
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
            offDevice("action=%s", safe("android.intent.action.MAIN")),
        )
    }

    @Test
    fun `sensitive withholds a number the type rule would carry`() {
        assertFalse(logArgumentMayLeaveDevice(sensitive(51.5)))
        assertEquals("lat=51.5", full("lat=%s", sensitive(51.5)))
        assertEquals("lat=$OFF_DEVICE_PLACEHOLDER", offDevice("lat=%s", sensitive(51.5)))
    }

    @Test
    fun `a summary the call site reduced itself is carried as it wrote it`() {
        // An app summarizes its own domain type and vouches for the result,
        // so it reads the same in both directions -- which is what `safe`
        // means. This used to be a two-field `LogSummary`, each call site
        // writing the value out twice; one tag on one rendering replaced it.
        val summary = safe("snooze(3h)")
        assertEquals("state=snooze(3h)", full("state=%s", summary))
        assertEquals("state=snooze(3h)", offDevice("state=%s", summary))
    }

    @Test
    fun `a double percent renders one literal percent`() {
        assertEquals("battery 80%", full("battery 80%%"))
    }

    @Test
    fun `a surplus placeholder is left in place rather than dropping the line`() {
        assertEquals("a=1 b=%s", full("a=%s b=%s", 1))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the former name still resolves to the same placeholder`() {
        // The alias exists so the rename alone cannot redden a consumer that
        // tracks @main; a consumer's own assertions must keep matching.
        assertEquals(OFF_DEVICE_PLACEHOLDER, REDACTED_PLACEHOLDER)
    }

    @Test
    fun `a surplus argument is appended and withheld like a placed one`() {
        assertEquals("a=1 [unplaced arg] secret", full("a=%s", 1, "secret"))
        assertEquals("a=1 [unplaced arg] $OFF_DEVICE_PLACEHOLDER", offDevice("a=%s", 1, "secret"))
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
        assertEquals("mode=CURRENT", offDevice("mode=%s", Leaky.CURRENT))
    }

    @Test
    fun `a plain enum still renders its name`() {
        assertEquals("mode=ARMED", full("mode=%s", Mode.ARMED))
    }

    @Test
    fun `a tag decides what may leave the device, not how a value is written`() {
        // `safe` used to mean both, and the second meaning was a way around
        // the type-only rendering a throwable argument gets. It now answers
        // only the boundary's question, so the
        // enum still renders its constant name rather than an override that
        // reads live state.
        assertTrue(logArgumentMayLeaveDevice(safe(Leaky.CURRENT)))
        assertEquals("mode=CURRENT", full("mode=%s", safe(Leaky.CURRENT)))
        assertEquals("mode=CURRENT", offDevice("mode=%s", safe(Leaky.CURRENT)))
    }

    @Test
    fun `a throwable inside a composite renders its type, not its message`() {
        // The fourth route by which an unknown `toString()` reached a message:
        // the collection's own, which calls the exception's.
        val boom = IllegalStateException("dialed +15550100")
        assertEquals("failure [java.lang.IllegalStateException]", full("failure %s", listOf(boom)))
        assertEquals(
            "failure {a=java.lang.IllegalStateException}",
            full("failure %s", mapOf("a" to boom)),
        )
        assertEquals("failure [java.lang.IllegalStateException]", full("failure %s", arrayOf(boom)))
        assertFalse(full("failure %s", listOf(listOf(boom))).contains("+15550100"))
    }

    @Test
    fun `an unknown type renders as its class name rather than its toString`() {
        val rendered = full("at %s", Holder())
        assertFalse(rendered, rendered.contains("ExampleWifi"))
        assertTrue(rendered, rendered.contains("Holder"))
    }

    @Test
    fun `a tagged value inside a composite renders its value, not the wrapper`() {
        // The tags are value classes, so boxed inside a collection they reached
        // the unknown-type branch and rendered as their own class name — losing
        // exactly the value the call site had gone out of its way to mark
        // (Codex, PR #3).
        assertEquals("at [51.5]", full("at %s", listOf(sensitive(51.5))))
        assertEquals("at [ARMED]", full("at %s", listOf(safe(Mode.ARMED))))
        assertEquals("at {a=51.5}", full("at %s", mapOf("a" to sensitive(51.5))))
        assertEquals("at [51.5]", full("at %s", arrayOf<Any?>(sensitive(51.5))))
    }

    @Test
    fun `a summary inside a composite renders as itself`() {
        // An element renders exactly as it would as the argument, which is what
        // keeps a call site's own summary from coming out as a wrapper's class
        // name when it happens to be inside a list.
        val summary = safe("place=a place")
        assertEquals("state [place=a place]", full("state %s", listOf(summary)))
        // An untagged composite is withheld whole, so reaching the element at
        // all takes a `safe` composite.
        assertEquals("state [place=a place]", offDevice("state %s", safe(listOf(summary))))
    }

    @Test
    fun `a sensitive element is withheld off device even inside a safe composite`() {
        // `safe` on the composite is the call site vouching for the composite;
        // `sensitive` inside it still dominates, as it does anywhere else.
        val tagged = safe(listOf("cell", sensitive(51.5)))
        assertTrue(logArgumentMayLeaveDevice(tagged))
        assertEquals("at [cell, 51.5]", full("at %s", tagged))
        assertEquals("at [cell, •••]", offDevice("at %s", tagged))
    }

    @Test
    fun `a safe composite still keeps its ordinary elements off device`() {
        // The other direction. Re-applying the whole floor per element would
        // withhold every `String` in a composite the call site had already
        // vouched for, which costs the diagnostic and protects nothing.
        assertEquals("sources [wifi, motion]", offDevice("sources %s", safe(listOf("wifi", "motion"))))
    }

    @Test
    fun `a tagged throwable inside a composite still renders its type`() {
        // The tag decides the floor, never the rendering — so wrapping one does
        // not reopen the route to the message from inside a composite either.
        val boom = IllegalStateException("dialed +15550100")
        assertEquals("failure [java.lang.IllegalStateException]", full("failure %s", listOf(safe(boom))))
        assertFalse(full("failure %s", listOf(safe(boom))).contains("+15550100"))
    }

    @Test
    fun `a composite of ordinary values still reads as its contents`() {
        // The other direction, and the reason elements are recursed into rather
        // than the whole composite refused: this is what a call site passing a
        // list actually wants to see.
        assertEquals("skipped [a, b]", full("skipped %s", listOf("a", "b")))
        assertEquals("counts {a=1, b=2}", full("counts %s", mapOf("a" to 1, "b" to 2)))
    }

    @Test
    fun `a self-referential composite renders rather than overflowing the stack`() {
        // Recording must not throw, and unbounded recursion here would throw
        // from inside the renderer.
        val loop = mutableListOf<Any>()
        loop.add(loop)
        val rendered = full("loop %s", loop)
        assertTrue(rendered, rendered.startsWith("loop ["))
    }

    @Test
    fun `a long composite is bounded rather than built whole`() {
        val rendered = full("ids %s", (1..100).toList())
        assertTrue(rendered, rendered.contains("…80 more"))
        assertFalse(rendered, rendered.contains(" 99,"))
    }

    @Test
    fun `a long map is bounded before its entries are rendered, not after`() {
        // Mapping the entry set eagerly rendered every entry and then threw all
        // but twenty away — the bound was there, and the cost it exists to
        // avoid was paid in full on the recording path.
        val counting = CountingMap((1..100).associate { "k$it" to it })
        val rendered = full("m %s", counting)
        assertTrue(rendered, rendered.contains("…80 more"))
        assertTrue("read ${counting.read} entries", counting.read <= 21)
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
        assertEquals("at $OFF_DEVICE_PLACEHOLDER", offDevice("at %s", safe(sensitive("ExampleWifi"))))
        // On device it is still rendered in full -- withholding is about what
        // leaves, not about what the user reads before sharing.
        assertEquals("at ExampleWifi", full("at %s", safe(sensitive("ExampleWifi"))))
    }

    @Test
    fun `a summary wrapped in a further tag still renders once`() {
        // Tags collapse before anything is decided, so a `safe` around a `safe`
        // renders the value rather than the wrapper -- which is what a
        // generated `toString()` on the outer tag would otherwise print.
        val summary = safe("at a place, 12m")
        assertEquals("snooze at a place, 12m", full("snooze %s", safe(summary)))
        assertEquals("snooze at a place, 12m", offDevice("snooze %s", safe(summary)))
    }

    @Test
    fun `a plain safe wrapper still vouches for its value`() {
        // The other direction: collapsing the tags must not cost `safe` its
        // whole purpose.
        assertTrue(logArgumentMayLeaveDevice(safe("ExampleWifi")))
        assertEquals("at ExampleWifi", offDevice("at %s", safe("ExampleWifi")))
    }
}
