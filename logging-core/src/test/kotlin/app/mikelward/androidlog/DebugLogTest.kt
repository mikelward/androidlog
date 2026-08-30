package app.mikelward.androidlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.TimeZone
import org.junit.After

class DebugLogTest {

    /** A fixed clock, so the rendered timestamp is pinned rather than sampled. */
    private fun log(
        maxEntries: Int = DebugLog.DEFAULT_MAX_ENTRIES,
        maxEntryChars: Int = DebugLog.DEFAULT_MAX_ENTRY_CHARS,
        maxTraceFrames: Int = DebugLog.DEFAULT_MAX_TRACE_FRAMES,
        maxCauseLinks: Int = DebugLog.DEFAULT_MAX_CAUSE_LINKS,
        maxPinnedEntries: Int = DebugLog.DEFAULT_MAX_PINNED_ENTRIES,
    ) = DebugLog(
        maxEntries = maxEntries,
        maxEntryChars = maxEntryChars,
        maxTraceFrames = maxTraceFrames,
        maxCauseLinks = maxCauseLinks,
        maxPinnedEntries = maxPinnedEntries,
        readMillis = { 0L },
    )


    /**
     * The recorded entries with the timezone markers filtered out. Most tests
     * are about what an entry says, not about the marker that anchors it; the
     * marker gets its own tests below.
     */
    private fun DebugLog.events(): List<String> =
        snapshot().filterNot { isMarker(it) }

    /**
     * Matches both marker shapes: the timestamped one written at a change, and
     * the untimestamped re-anchor prepended when eviction carried the last one
     * off the front.
     */
    private fun isMarker(line: String): Boolean =
        " ${DebugLog.MARKER_LEVEL} timezone offset " in line ||
            line.startsWith("${DebugLog.MARKER_LEVEL} timezone offset ")

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `an event is recorded with its level, and the floor applied at ingestion`() {
        // One rendering, and it is the reduced one. The buffer never holds a
        // form the floor would withhold, so nothing downstream -- a sink, a
        // file, a report -- has to remember to ask for the safe version.
        val log = log()
        log.event("joined %s at %s", "ExampleWifi", 3)
        val line = log.events().single()
        assertTrue(line, line.endsWith(" D joined $REDACTED_PLACEHOLDER at 3"))
    }

    @Test
    fun `a value the call site vouches for survives ingestion`() {
        // The floor is a type rule, so an app that has already reduced a value
        // says so and keeps it -- which is how a consumer that redacts before
        // logging (a masked number, an account token) stays diagnosable.
        val log = log()
        log.event("joined %s at %s", safe("+61\u2022\u2022\u2022\u2022\u2022\u2022678 (len=12)"), 3)
        val line = log.events().single()
        assertTrue(line, line.endsWith(" D joined +61\u2022\u2022\u2022\u2022\u2022\u2022678 (len=12) at 3"))
    }

    @Test
    fun `a throwable argument still names its type`() {
        // Withheld, it took the one thing a failure line is read for. Its
        // rendering is the class name and nothing else, so it names no user.
        val log = log()
        log.event("gave up on %s", IllegalStateException("secret"))
        val line = log.events().single()
        assertTrue(line, line.endsWith(" D gave up on java.lang.IllegalStateException"))
        assertFalse(line, line.contains("secret"))
    }

    @Test
    fun `the buffer is bounded and the oldest entry falls off the front`() {
        val log = log(maxEntries = 3)
        repeat(5) { log.event("entry %s", it) }
        val entries = log.events()
        assertEquals(3, entries.size)
        assertTrue(entries.first().endsWith("entry 2"))
        assertTrue(entries.last().endsWith("entry 4"))
        assertTrue(log.snapshot().first().startsWith("I timezone offset "))
    }

    @Test
    fun `disabling recording empties the buffer and stops further entries`() {
        val log = log()
        log.event("before")
        log.setRecording(false)
        assertFalse(log.isRecording)
        assertEquals(emptyList<String>(), log.events())
        log.event("during")
        assertEquals(emptyList<String>(), log.events())
    }

    @Test
    fun `re-enabling records afresh rather than resurrecting what was cleared`() {
        val log = log()
        log.event("before")
        log.setRecording(false)
        log.setRecording(true)
        log.event("after")
        assertTrue(log.events().single().endsWith("after"))
    }

    @Test
    fun `a sink receives each entry and one that throws reaches neither caller nor sibling`() {
        val log = log()
        val seen = mutableListOf<String>()
        log.addSink { error("this sink is broken") }
        log.addSink { seen += it }
        log.event("recorded %s", 1)
        val delivered = seen.filter { " D " in it }
        assertEquals(1, delivered.size)
        assertTrue(delivered.single().endsWith("recorded 1"))
        assertEquals(1, log.events().size)
    }

    @Test
    fun `a removed sink stops receiving entries`() {
        val log = log()
        val seen = mutableListOf<String>()
        val sink = DebugLog.Sink { seen += it }
        log.addSink(sink)
        log.event("first")
        log.removeSink(sink)
        log.event("second")
        assertEquals(1, seen.count { " D " in it })
    }

    @Test
    fun `a failure renders the exception type and frames but never its message`() {
        val log = log()
        log.failure(IllegalStateException("dialed +15550100"), "route failed")
        val line = log.events().single()
        assertTrue(line, "java.lang.IllegalStateException" in line)
        assertTrue(line, "\tat " in line)
        assertFalse(line, "+15550100" in line)
    }

    @Test
    fun `a cause chain renders every link without any message`() {
        val log = log()
        val cause = IllegalArgumentException("ssid ExampleWifi")
        log.failure(RuntimeException("wrapper", cause), "lookup failed")
        val line = log.events().single()
        assertTrue(line, "Caused by: java.lang.IllegalArgumentException" in line)
        assertFalse(line, "ExampleWifi" in line)
        assertFalse(line, "wrapper" in line)
    }

    @Test
    fun `a cyclic cause chain terminates instead of hanging`() {
        val log = log()
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        log.failure(first, "cyclic")
        assertEquals(1, log.events().size)
    }

    @Test
    fun `a long cause chain is elided rather than consuming the entry budget`() {
        val log = log(maxCauseLinks = 2)
        var throwable: Throwable = RuntimeException("root")
        repeat(5) { throwable = RuntimeException("link", throwable) }
        log.failure(throwable, "deep")
        assertTrue(log.events().single().contains("more causes elided"))
    }

    @Test
    fun `an over-long entry is truncated rather than dominating the buffer`() {
        val log = log(maxEntryChars = 40)
        log.event("%s", safe("x".repeat(200)))
        val line = log.events().single()
        assertTrue(line, line.endsWith("…(truncated)"))
        assertEquals(40 + "…(truncated)".length, line.length)
    }

    @Test
    fun `a throwable passed to warning is rerouted to failure instead of rendered`() {
        val log = log()
        log.warning("gave up after %s", 3, IllegalStateException("dialed +15550100"))
        val line = log.events().single()
        assertTrue(line, "use failure()" in line)
        assertTrue(line, "gave up after 3" in line)
        assertTrue(line, "java.lang.IllegalStateException" in line)
        // The reason the reroute exists: rendered as an argument it would have
        // carried its own message into the log through toString().
        assertFalse(line, "+15550100" in line)
    }

    @Test
    fun `a warning without a throwable records at warning level`() {
        val log = log()
        log.warning("no fix within %ss", 10)
        assertTrue(log.events().single().contains(" W no fix within 10s"))
    }

    @Test
    fun `a throwable passed as an argument renders its type but never its message`() {
        val log = log()
        log.event("gave up on %s", IllegalStateException("dialed +15550100"))
        val line = log.events().single()
        assertTrue(line, "java.lang.IllegalStateException" in line)
        assertFalse(line, "+15550100" in line)
    }

    @Test
    fun `a throwable in a surplus argument to failure renders no message either`() {
        val log = log()
        log.failure(
            RuntimeException("outer"),
            "gave up",
            IllegalStateException("ssid ExampleWifi"),
        )
        val line = log.events().single()
        assertFalse(line, "ExampleWifi" in line)
        assertFalse(line, "outer" in line)
    }

    @Test
    fun `wrapping a throwable in safe does not let its message through`() {
        val log = log()
        log.event("gave up on %s", safe(IllegalStateException("dialed +15550100")))
        assertFalse(log.events().single(), "+15550100" in log.events().single())
    }

    @Test
    fun `disabling waits for an in-flight sink delivery and nothing reaches a sink after`() {
        val log = log()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        log.addSink { line ->
            seen += line
            entered.countDown()
            release.await()
        }

        val recorder = thread { log.event("in flight") }
        assertTrue("recorder never reached the sink", entered.await(5, TimeUnit.SECONDS))

        val disabler = thread { log.setRecording(false) }
        // The gate holds the disable open while the sink is still in its call.
        disabler.join(250)
        assertTrue("setRecording(false) returned while a delivery was in flight", disabler.isAlive)

        release.countDown()
        recorder.join(5_000)
        disabler.join(5_000)
        assertFalse(disabler.isAlive)

        // And the point of all of it: once the disable has returned, nothing
        // more reaches a sink.
        log.event("after the opt-out")
        assertEquals(
            listOf("in flight"),
            seen.filter { " D " in it }.map { it.substringAfter(" D ") },
        )
    }

    @Test
    fun `the first entry is anchored by a marker naming the offset`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        log.event("first")
        val lines = log.snapshot()
        assertEquals(2, lines.size)
        assertEquals("I timezone offset Z", lines.first())
        assertTrue(lines.last().endsWith(" D first"))
    }

    @Test
    fun `an unchanged offset emits no further marker`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        log.event("first")
        log.event("second")
        assertEquals(1, log.snapshot().count { isMarker(it) })
    }

    @Test
    fun `a changed offset emits a new marker before the next entry`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        log.event("before the flight")
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        log.event("after the flight")
        val lines = log.snapshot()
        assertEquals(4, lines.size)
        assertEquals("I timezone offset Z", lines[0])
        assertEquals("I timezone offset +09:00", lines[2])
        assertTrue(lines[3].endsWith(" D after the flight"))
    }

    @Test
    fun `a timestamp carries no offset of its own`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        log.event("plain")
        val entry = log.events().single()
        // MM-dd HH:mm:ss.SSS and nothing more before the level.
        assertTrue(entry, Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d D plain$""").matches(entry))
    }

    @Test
    fun `re-enabling after a disable announces the offset again`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        log.event("before")
        log.setRecording(false)
        log.setRecording(true)
        log.event("after")
        // The clear took the first marker with it, so the surviving entry is
        // anchored rather than left reading against nothing.
        assertEquals(2, log.snapshot().size)
        assertEquals("I timezone offset Z", log.snapshot().first())
    }

    @Test
    fun `an entry conceived before a disable does not land after a re-enable`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        // The clock read happens before the gate, which is exactly where a
        // recorder can be descheduled -- so blocking it there reproduces the
        // window deterministically, with no sleeps.
        val log = DebugLog(readMillis = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
            0L
        })

        val recorder = thread { log.event("conceived before the opt-out") }
        assertTrue("recorder never reached the clock", entered.await(5, TimeUnit.SECONDS))

        log.setRecording(false)
        log.setRecording(true)
        release.countDown()
        recorder.join(5_000)

        // Recording is on again, but this entry belongs to the period the
        // opt-out discarded, so it must not appear in the new one.
        assertEquals(emptyList<String>(), log.snapshot())
    }

    @Test
    fun `the marker and the entry it anchors share one clock reading`() {
        // A clock that advances a minute per read: with two reads the marker
        // and its entry would carry different stamps, and across a transition
        // could carry different offsets too.
        val ticks = AtomicLong(0)
        val log = DebugLog(readMillis = { ticks.getAndAdd(60_000) })
        val seen = mutableListOf<String>()
        log.addSink { seen += it }
        log.event("first")
        // The sink stream is where a timestamped marker is written, so that is
        // where the two stamps have to agree.
        assertEquals(2, seen.size)
        assertEquals(seen[1].substringBefore(" D "), seen[0].substringBefore(" I "))
        assertEquals(1, ticks.get() / 60_000)
    }

    @Test
    fun `an anchor survives eviction so a long session is never bare timestamps`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log(maxEntries = 5)
        // Comfortably past the bound, which is the ordinary case rather than an
        // exotic one.
        repeat(50) { log.event("entry %s", it) }
        val lines = log.snapshot()
        // Five entries plus the synthesized anchor, which costs no buffer slot
        // now that it is not stored.
        assertEquals(6, lines.size)
        assertEquals("I timezone offset +09:00", lines.first())
        // Exactly one anchor: the run never changes offset.
        assertEquals(1, lines.count { isMarker(it) })
        assertTrue(lines.last().endsWith("entry 49"))
    }

    @Test
    fun `the re-anchor carries no timestamp, since it sits ahead of older lines`() {
        val log = log(maxEntries = 3)
        repeat(10) { log.event("entry %s", it) }
        val anchor = log.snapshot().first()
        // A timestamp here would run backwards against the line below it.
        assertFalse(anchor, Regex("""^\d\d-\d\d """).containsMatchIn(anchor))
    }

    @Test
    fun `a single-slot buffer keeps its entry rather than only its header`() {
        val log = log(maxEntries = 1)
        log.event("first")
        log.event("second")
        assertTrue(log.events().single().endsWith(" D second"))
    }

    @Test
    fun `a non-positive buffer bound is rejected at construction`() {
        // It used to call removeFirst() on an empty deque from inside event(),
        // outside the runCatching, so a log call threw.
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxEntries = -1) }
    }

    @Test
    fun `a full buffer crossing an offset change keeps the older run anchored too`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log(maxEntries = 6)
        repeat(6) { log.event("before %s", it) }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        repeat(3) { log.event("after %s", it) }

        val lines = log.snapshot()
        // The window straddles the change, so it carries two anchors: one for
        // the older run still retained at the head, one for the new run.
        assertEquals(listOf("I timezone offset Z", "I timezone offset +09:00"), lines.filter { isMarker(it) })
        assertTrue(lines.first(), lines.first() == "I timezone offset Z")
        // And the anchor sits immediately before the run it describes.
        val changeAt = lines.indexOf("I timezone offset +09:00")
        assertTrue(lines[changeAt - 1], "before " in lines[changeAt - 1])
        assertTrue(lines[changeAt + 1], "after 0" in lines[changeAt + 1])
    }

    @Test
    fun `a sink sees lines in the order the buffer took them`() {
        val log = log()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        log.addSink { line ->
            seen += line
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
        }

        val slow = thread { log.event("first") }
        assertTrue("first recorder never reached the sink", entered.await(5, TimeUnit.SECONDS))
        // A second recorder must not be able to deliver past the one holding
        // the monitor -- that is the interleaving that used to reorder a
        // persistent sink against snapshot().
        val fast = thread { log.event("second") }
        fast.join(250)
        assertTrue("a second recorder delivered while the first was mid-delivery", fast.isAlive)

        release.countDown()
        slow.join(5_000)
        fast.join(5_000)
        assertEquals(
            listOf("first", "second"),
            seen.filter { " D " in it }.map { it.substringAfter(" D ") },
        )
    }

    @Test
    fun `a sink added after recording has begun is anchored before its first entry`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        log.event("recorded before the sink existed")

        val seen = mutableListOf<String>()
        log.addSink { seen += it }
        // Nothing is pushed at registration; the anchor rides with the next
        // entry, ahead of it.
        assertEquals(emptyList<String>(), seen)

        log.event("first entry the sink sees")
        assertEquals(2, seen.size)
        assertTrue(seen[0], seen[0].endsWith(" I timezone offset +09:00"))
        assertTrue(seen[1], seen[1].endsWith(" D first entry the sink sees"))
    }

    @Test
    fun `a sink added before anything is recorded is not anchored twice`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        val seen = mutableListOf<String>()
        log.addSink { seen += it }
        // Nothing recorded yet, so there is no offset to announce; the marker
        // arrives inline with the first entry as usual.
        assertEquals(emptyList<String>(), seen)
        log.event("first")
        assertEquals(2, seen.size)
        assertTrue(seen[0], seen[0].endsWith(" I timezone offset +09:00"))
    }

    @Test
    fun `an entry begun while recording was off does not land after a re-enable`() {
        val log = log()
        log.setRecording(false)
        log.event("begun while opted out")
        log.setRecording(true)
        assertEquals(emptyList<String>(), log.snapshot())
    }

    @Test
    fun `a redundant enable does not discard an in-flight entry`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val log = DebugLog(readMillis = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await()
            }
            0L
        })

        val recorder = thread { log.event("in flight") }
        assertTrue("recorder never reached the clock", entered.await(5, TimeUnit.SECONDS))
        // Recording is already on, so this must not publish a new session --
        // otherwise a setting that did not move would throw the entry away.
        log.setRecording(true)
        release.countDown()
        recorder.join(5_000)

        assertTrue(log.events().single().endsWith(" D in flight"))
    }

    @Test
    fun `truncation never strands half a surrogate pair`() {
        // The rendered prefix (timestamp plus level) shifts where a given
        // budget lands, so rather than guess one index that splits the pair,
        // sweep a range wide enough that some budget certainly lands inside
        // one. A single hand-picked budget is exactly how this test would
        // pass while testing nothing.
        val message = "\uD834\uDD1E".repeat(12)
        var sawTruncation = false
        for (budget in 20..44) {
            val log = log(maxEntryChars = budget)
            log.event("%s", safe(message))
            val entry = log.events().single()
            if (!entry.endsWith("…(truncated)")) continue
            sawTruncation = true
            val body = entry.removeSuffix("…(truncated)")
            assertFalse("budget=$budget left a lone surrogate: $body", body.last().isHighSurrogate())
        }
        assertTrue("no budget in the sweep actually truncated", sawTruncation)
    }

    @Test
    fun `a late sink that throws on its anchor is anchored on the next entry`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        log.event("recorded before the sink existed")

        val seen = mutableListOf<String>()
        val throwOnce = AtomicBoolean(true)
        log.addSink { line ->
            if (throwOnce.compareAndSet(true, false)) error("sink briefly unwell")
            seen += line
        }

        // The anchor throws, so it is not recorded as delivered -- but the
        // entry still goes out. A header is not worth withholding the
        // diagnostic for.
        log.event("first entry, unanchored")
        assertEquals(1, seen.size)
        assertTrue(seen[0], seen[0].endsWith(" D first entry, unanchored"))

        // And the next entry offers the anchor again, so the sink does not
        // spend the rest of the process on bare timestamps.
        log.event("second entry")
        assertTrue(seen[1], seen[1].endsWith(" I timezone offset +09:00"))
        assertTrue(seen[2], seen[2].endsWith(" D second entry"))
    }

    @Test
    fun `a removed sink stops waiting for its anchor`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        log.event("before")

        val seen = mutableListOf<String>()
        val sink = DebugLog.Sink { line ->
            if (seen.isEmpty() && line.startsWith("I timezone")) error("refuse the anchor")
            seen += line
        }
        log.addSink(sink)
        log.removeSink(sink)
        log.event("after")
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `a non-positive entry-length bound is rejected at construction`() {
        // A negative bound sent every entry into take(-1), which throws inside
        // the runCatching around rendering -- so the log silently dropped
        // everything rather than truncating it.
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxEntryChars = 0) }
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxEntryChars = -1) }
    }

    @Test
    fun `two sinks that compare equal are both registered and both anchored`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val log = log()
        // Equal by value, distinct by reference -- two real destinations.
        class Recorder(val lines: MutableList<String>) : DebugLog.Sink {
            override fun log(line: String) { lines += line }
            override fun equals(other: Any?) = other is Recorder
            override fun hashCode() = 1
        }
        val first = Recorder(mutableListOf())
        val second = Recorder(mutableListOf())
        log.addSink(first)
        log.addSink(second)

        log.event("delivered")
        for (recorder in listOf(first, second)) {
            assertEquals(2, recorder.lines.size)
            assertTrue(recorder.lines[0], recorder.lines[0].endsWith(" I timezone offset +09:00"))
            assertTrue(recorder.lines[1], recorder.lines[1].endsWith(" D delivered"))
        }
    }

    @Test
    fun `a sink whose offset-change marker throws is offered it again`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        val seen = mutableListOf<String>()
        val throwOnNextMarker = AtomicBoolean(false)
        log.addSink { line ->
            if (" I timezone offset " in line && throwOnNextMarker.compareAndSet(true, false)) {
                error("sink refused the marker")
            }
            seen += line
        }
        log.event("before")

        // The offset moves and the sink throws on that marker. The old global
        // flag advanced anyway and never re-announced.
        throwOnNextMarker.set(true)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        log.event("after the change")
        assertTrue(seen.none { it.endsWith("+09:00") })

        log.event("later still")
        assertTrue(seen.any { it.endsWith(" I timezone offset +09:00") })
    }

    @Test
    fun `a sink that unregisters itself from inside log does not break the fan-out`() {
        // The fan-out used to iterate the live registration map, so a sink
        // touching the registry from its own callback threw
        // ConcurrentModificationException out of the iterator -- outside the
        // runCatching that contains the callback, and so out of event().
        //
        // Two self-removing sinks, not one: the map's iteration order is by
        // identity hash and so is not ours to choose, and a lone mutator that
        // happened to come last would modify nothing the iterator went on to
        // read. With two of three registered, at least one is followed by
        // another step, whatever the order.
        val log = log()
        val other = mutableListOf<String>()
        log.addSink { other += it }
        repeat(2) {
            lateinit var self: DebugLog.Sink
            self = DebugLog.Sink { log.removeSink(self) }
            log.addSink(self)
        }

        log.event("first")
        log.event("second")

        // The self-removing sinks took themselves out and the other carried on.
        assertTrue(other.toString(), other.any { it.endsWith(" D first") })
        assertTrue(other.toString(), other.any { it.endsWith(" D second") })
    }

    @Test
    fun `a sink that unregisters itself on its anchor is not put back by that anchor`() {
        // Recording a successful anchor writes the offset back against the
        // sink. Done blindly that is a `put`, so a sink that left during its
        // own marker was resurrected into the registry and went on receiving
        // entries it had unregistered from.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        val seen = mutableListOf<String>()
        lateinit var self: DebugLog.Sink
        self = DebugLog.Sink { line ->
            seen += line
            log.removeSink(self)
        }
        log.addSink(self)

        log.event("first")
        log.event("second")

        // It leaves on the marker, so the marker is all it ever sees.
        assertEquals(listOf("01-01 00:00:00.000 I timezone offset Z"), seen)
    }

    @Test
    fun `a sink that re-registers itself on its anchor waits for a fresh anchor`() {
        // The shape a rotating file sink takes: it swaps its destination and
        // re-registers to be anchored again. Keyed on presence alone, the
        // fan-out could not tell the fresh registration from the one it had
        // already read, so it marked the new one anchored by a marker the new
        // destination never saw -- and delivered the entry there bare.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val log = log()
        val seen = mutableListOf<String>()
        val rotated = AtomicBoolean(false)
        lateinit var self: DebugLog.Sink
        self = DebugLog.Sink { line ->
            seen += line
            if (rotated.compareAndSet(false, true)) {
                log.removeSink(self)
                log.addSink(self)
            }
        }
        log.addSink(self)

        log.event("first")
        // The rotation happened on the anchor, so the entry behind it belongs
        // to the destination that is gone; the new one starts clean.
        assertEquals(listOf("01-01 00:00:00.000 I timezone offset Z"), seen)

        log.event("second")
        assertEquals(
            listOf(
                "01-01 00:00:00.000 I timezone offset Z",
                "01-01 00:00:00.000 I timezone offset Z",
                "01-01 00:00:00.000 D second",
            ),
            seen,
        )
    }

    @Test
    fun `a sink that registers another from inside log does not break the fan-out`() {
        val log = log()
        val late = mutableListOf<String>()
        log.addSink { }
        repeat(2) {
            val once = AtomicBoolean(false)
            log.addSink {
                if (once.compareAndSet(false, true)) log.addSink { line -> late += line }
            }
        }

        log.event("first")
        log.event("second")

        // The new sinks joined after the entry that registered them had gone
        // out, so they see the next one and not that one.
        assertTrue(late.toString(), late.none { it.endsWith(" D first") })
        assertTrue(late.toString(), late.any { it.endsWith(" D second") })
    }

    @Test
    fun `an out-of-range trace or cause bound is rejected at construction`() {
        // Neither threw, they corrupted: a negative frame bound made the kept
        // count negative, so the entry claimed more omitted frames than the
        // trace had, and a cause bound below one elided the throwable before
        // even its type -- an entry saying causes were omitted while naming no
        // failure at all.
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxTraceFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxCauseLinks = 0) }
        assertThrows(IllegalArgumentException::class.java) { DebugLog(maxCauseLinks = -1) }
    }

    @Test
    fun `the tightest legal bounds still name the type`() {
        // The other direction: "types, no frames, no causes" is a coherent
        // setting, and rejecting it would cost a real one.
        val log = log(maxTraceFrames = 0, maxCauseLinks = 1)
        log.failure(IllegalStateException(IllegalArgumentException()), "boom")
        val entry = log.events().single()
        assertTrue(entry, "java.lang.IllegalStateException" in entry)
        assertTrue(entry, "more causes elided" in entry)
    }

    @Test
    fun `a rerouted warning keeps every argument in its own placeholder`() {
        // Removing the throwable from the argument list shifted every later
        // value one placeholder left, so this rendered `cause 3 after %s`.
        val log = log()
        log.warning("cause %s after %s", IllegalStateException("dialed +15550100"), 3)
        val line = log.events().single()
        assertTrue(line, "cause java.lang.IllegalStateException after 3" in line)
        assertFalse(line, "+15550100" in line)
    }

    @Test
    fun `a rerouted warning keeps a repeated throwable in both placeholders`() {
        // `filterNot { it === throwable }` dropped every occurrence of the same
        // instance, not just the one it rerouted.
        val log = log()
        val boom = IllegalStateException("dialed +15550100")
        log.warning("%s then %s", boom, boom)
        val line = log.events().single()
        assertTrue(
            line,
            "java.lang.IllegalStateException then java.lang.IllegalStateException" in line,
        )
    }

    @Test
    fun `a throwable wrapped in a tag is rerouted to failure as well`() {
        // Matching on the argument's own type saw the wrapper, not the
        // throwable inside it, so the reroute never fired: the line carried the
        // exception's type with neither its frames nor the marker saying the
        // call site had reached for the wrong function.
        for (tagged in listOf(
            safe(IllegalStateException("dialed +15550100")),
            sensitive(IllegalStateException("dialed +15550100")),
        )) {
            val log = log()
            log.warning("gave up on %s", tagged)
            val line = log.events().single()
            assertTrue(line, "use failure()" in line)
            assertTrue(line, "java.lang.IllegalStateException" in line)
            // The frames are what the reroute buys, and what was being lost.
            assertTrue(line, "\n\tat " in line)
            assertFalse(line, "+15550100" in line)
        }
    }

    @Test
    fun `a sink that records from inside log has that entry dropped`() {
        // Both the gate and the buffer monitor are reentrant, so the nested
        // entry used to append and fan out completely before the outer one
        // reached the next sink -- a later sink saw them in the opposite order
        // to snapshot().
        val log = log()
        val once = AtomicBoolean(false)
        log.addSink {
            if (once.compareAndSet(false, true)) log.event("from inside the sink")
        }
        log.event("outer")
        assertEquals(listOf("01-01 00:00:00.000 D outer"), log.events())
    }

    @Test
    fun `a dropped nested entry is reported on the next entry, once`() {
        // The entries are gone; that they existed is not.
        val log = log()
        val once = AtomicBoolean(false)
        log.addSink {
            if (once.compareAndSet(false, true)) {
                log.event("first nested")
                log.event("second nested")
            }
        }
        log.event("outer")
        log.event("later")

        val lines = log.events()
        val notice = lines.single { "dropped" in it }
        assertTrue(notice, notice.contains(" W 2 entries dropped"))
        assertTrue(notice, notice.contains("recorded from inside log()"))
        assertTrue(
            lines.toString(),
            lines.indexOf(notice) < lines.indexOfFirst { it.endsWith(" D later") },
        )

        log.event("later still")
        assertEquals(1, log.events().count { "dropped" in it })
    }

    @Test
    fun `a disable discards a pending drop report along with the buffer`() {
        // A drop counted but not yet reported belongs to the session being
        // discarded, and *records afresh* means the next session opens on its
        // own state rather than on a warning about the last one.
        val log = log()
        val once = AtomicBoolean(false)
        log.addSink { if (once.compareAndSet(false, true)) log.event("nested") }
        log.event("outer")

        log.setRecording(false)
        log.setRecording(true)
        log.event("fresh")

        assertEquals(listOf("01-01 00:00:00.000 D fresh"), log.events())
    }

    @Test
    fun `a single-slot buffer still reports a drop to its sinks`() {
        // The ring can only ever hold the newest line at this bound, so a
        // notice put there is evicted by the entry behind it before anyone
        // reads it. A sink is append-only, so that is where the report keeps.
        val log = log(maxEntries = 1)
        val seen = mutableListOf<String>()
        val once = AtomicBoolean(false)
        log.addSink {
            seen += it
            if (once.compareAndSet(false, true)) log.event("nested")
        }
        log.event("outer")
        log.event("later")

        assertTrue(seen.toString(), seen.any { it.contains(" W 1 entry dropped") })
        // The ring holds what a one-slot ring holds: the newest line.
        assertEquals(listOf("01-01 00:00:00.000 D later"), log.events())
    }

    @Test
    fun `a sink recording from inside log still receives later entries`() {
        // The delivering-thread marker has to be cleared, or every later entry
        // on that thread would be dropped as if it were nested too.
        val log = log()
        val seen = mutableListOf<String>()
        val once = AtomicBoolean(false)
        log.addSink {
            seen += it
            if (once.compareAndSet(false, true)) log.event("nested")
        }
        log.event("outer")
        log.event("after")
        assertTrue(seen.toString(), seen.any { it.endsWith(" D after") })
    }

    @Test
    fun `disabling recording tells the sinks the buffer was cleared`() {
        // A sink holding a durable copy has to hear about this, or "off" means
        // off only in memory and its copy outlives the opt-out.
        val log = log()
        var cleared = 0
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared() {
                cleared++
            }
        })

        log.event("before")
        assertEquals(0, cleared)
        log.setRecording(false)
        assertEquals(1, cleared)
    }

    @Test
    fun `enabling recording does not, and a redundant disable does not repeat`() {
        // The other direction: this is not a general "something changed" signal,
        // so a sink cannot use it to mean anything but "the buffer is empty now".
        val log = log()
        var cleared = 0
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared() {
                cleared++
            }
        })

        log.setRecording(false)
        log.setRecording(true)
        assertEquals("a re-enable clears nothing", 1, cleared)
        log.setRecording(false)
        log.setRecording(false)
        assertEquals("a redundant disable is a no-op", 2, cleared)
    }

    @Test
    fun `a sink recording from inside onCleared has that entry dropped`() {
        // The same prohibition as `log()`, for the same reason: this runs under
        // the buffer's monitor, on a thread already inside the log.
        val log = log()
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared() {
                log.event("from inside onCleared")
            }
        })

        log.event("before")
        log.setRecording(false)
        log.setRecording(true)
        log.event("after")

        assertFalse(log.events().toString(), log.events().any { it.contains("from inside onCleared") })
        assertTrue(log.events().toString(), log.events().any { it.contains("dropped") })
    }

    @Test
    fun `a sink that throws from onCleared does not stop the others`() {
        val log = log()
        var reached = false
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared(): Unit = throw IllegalStateException("boom")
        })
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared() {
                reached = true
            }
        })

        log.setRecording(false)
        assertTrue(reached)
    }

    @Test
    fun `a sink that fails to clear is named once recording resumes`() {
        // The sink may still be holding exactly what the opt-out was meant to
        // remove, and nothing else in the system knows. Nothing can be said at
        // the time — recording is off by then — so it is held.
        val log = log()
        log.addSink(object : DebugLog.Sink {
            override fun log(line: String) = Unit
            override fun onCleared(): Unit = throw IllegalStateException("boom")
        })

        log.setRecording(false)
        assertEquals("nothing is recorded while it is off", emptyList<String>(), log.events())

        log.setRecording(true)
        log.event("after")
        val notice = log.events().single { "failed to clear" in it }
        assertTrue(notice, notice.contains("1 sink failed to clear a saved copy"))
        assertTrue("and names what failed", notice.contains("IllegalStateException"))
        assertTrue(
            log.events().toString(),
            log.events().indexOf(notice) < log.events().indexOfFirst { it.endsWith(" D after") },
        )

        log.event("later")
        assertEquals("said once", 1, log.events().count { "failed to clear" in it })
    }

    // -------------------------------------------------- did the entry land?

    @Test
    fun `a recorded entry says it landed and a discarded one says it did not`() {
        // What a once-per-spell failure guard has to latch on. `isRecording`
        // cannot answer it: the gate can close between the question and the
        // entry, and it says nothing about the other two drops below.
        val log = log()
        assertTrue("recording, so it landed", log.event("landed"))

        log.setRecording(false)
        assertFalse("the gate is closed", log.event("discarded"))
        assertFalse("and a warning is no different", log.warning("discarded"))
        assertFalse(
            "nor a failure",
            log.failure(IllegalStateException("boom"), "discarded"),
        )
    }

    @Test
    fun `an entry recorded from inside a sink says it did not land`() {
        // The second drop, and one no gate reading could have predicted: the
        // log is recording, and this entry still goes nowhere.
        val log = log()
        val answers = mutableListOf<Boolean>()
        val once = AtomicBoolean(false)
        log.addSink {
            if (once.compareAndSet(false, true)) answers += log.event("from inside the sink")
        }
        log.event("outer")

        assertEquals(listOf(false), answers)
    }

    @Test
    fun `a rerouted warning answers for the failure it became`() {
        // `warning` with a throwable is passed on to `failure`, so its answer
        // has to be that call's, not a fresh guess.
        val log = log()
        assertTrue(log.warning("boom %s", IllegalStateException("boom")))

        log.setRecording(false)
        assertFalse(log.warning("boom %s", IllegalStateException("boom")))
    }

    // --------------------------------------------------------------- pinning

    @Test
    fun `a pinned line outlives the ring evicting it`() {
        // The whole reason pinning exists: the lines that say how this run began
        // are written once, and a busy device evicts them long before anyone
        // shares a report.
        val log = log(maxEntries = 3)
        log.pinnedEvent("started after %s", 7)
        repeat(5) { log.event("ordinary %s", it) }

        assertFalse(log.snapshot().any { "started after" in it })
        assertTrue(log.pinnedSnapshot().any { "started after 7" in it })
        assertTrue(log.boundedSnapshot(1_000, 1_000).any { "started after 7" in it })
    }

    @Test
    fun `a pinned line the ring still holds is not written twice`() {
        val log = log()
        log.pinnedEvent("started after %s", 7)
        log.event("ordinary")

        val lines = log.boundedSnapshot(1_000, 1_000)
        assertEquals(lines.toString(), 1, lines.count { "started after 7" in it })
    }

    @Test
    fun `pinned lines the ring no longer holds come first, in order`() {
        // They are older than everything in the tail by construction -- absent
        // from it only because newer lines pushed them out -- so prepending is
        // chronological rather than a guess.
        val log = log(maxEntries = 2)
        log.pinnedEvent("first")
        log.pinnedEvent("second")
        repeat(2) { log.event("ordinary %s", it) }

        val events = log.boundedSnapshot(1_000, 1_000).filterNot { isMarker(it) }
        assertEquals(4, events.size)
        assertTrue(events.toString(), "first" in events[0])
        assertTrue(events.toString(), "second" in events[1])
        assertTrue(events.toString(), "ordinary 0" in events[2])
        assertTrue(events.toString(), "ordinary 1" in events[3])
    }

    @Test
    fun `turning recording off empties the pinned buffer too`() {
        // Nothing else in this class ever removes a pinned line -- outliving
        // eviction is the point -- so an opt-out that missed them would keep
        // recorded content in memory and hand it to the next persisted file.
        val log = log()
        log.pinnedEvent("started after %s", 7)

        log.setRecording(false)

        assertEquals(emptyList<String>(), log.pinnedSnapshot())
        assertEquals(emptyList<String>(), log.boundedSnapshot(1_000, 1_000))
    }

    @Test
    fun `the pinned reserve survives a recent log that fills its whole budget`() {
        // The persisted file is a tail, so without a reserve the start-up lines
        // are the first thing trimmed -- exactly what pinning exists to keep.
        val log = log()
        log.pinnedEvent("started after %s", 7)
        repeat(200) { log.event("ordinary %s", it) }

        val lines = log.boundedSnapshot(pinnedBudgetChars = 1_000, recentBudgetChars = 200)
        assertTrue(lines.toString(), lines.any { "started after 7" in it })
        // And the reserve is not simply extra room for everything: the recent
        // budget still bit.
        assertTrue(lines.toString(), lines.none { "ordinary 0 " in it })
    }

    @Test
    fun `one shared budget is not enough, which is what the reserve answers`() {
        // The same log with nothing held back: the pinned line is trimmed away.
        val log = log()
        log.pinnedEvent("started after %s", 7)
        repeat(200) { log.event("ordinary %s", it) }

        val lines = log.boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = 200)
        assertTrue(lines.toString(), lines.none { "started after 7" in it })
        // Absent because the section was not written, not because it was
        // clamped down to a stub: a budget of zero that answers with a
        // truncation marker and an anchor passes the line above while
        // overrunning by thirty characters (Codex, PR #7).
        assertTrue(lines.toString(), lines.none { "…(truncated)" in it })
    }

    @Test
    fun `the pinned buffer is bounded and evicts its oldest`() {
        val log = log(maxPinnedEntries = 2)
        log.pinnedEvent("first")
        log.pinnedEvent("second")
        log.pinnedEvent("third")

        val pinned = log.pinnedSnapshot().filterNot { isMarker(it) }
        assertEquals(2, pinned.size)
        assertTrue(pinned.toString(), "second" in pinned[0])
        assertTrue(pinned.toString(), "third" in pinned[1])
    }

    @Test
    fun `a non-positive pinned bound is rejected at construction`() {
        // Same failure as the ring's: the first pinned append would call
        // removeFirst() on an empty deque, outside the runCatching that keeps
        // recording from throwing.
        assertThrows(IllegalArgumentException::class.java) { log(maxPinnedEntries = 0) }
    }

    @Test
    fun `the floor applies to a pinned line like any other`() {
        val log = log()
        log.pinnedEvent("started as %s", "unvouched")

        assertTrue(log.pinnedSnapshot().toString(), log.pinnedSnapshot().any { "started as \u2022\u2022\u2022" in it })
    }

    @Test
    fun `a prepended pinned line is anchored rather than left bare`() {
        // It sits ahead of the ring's own oldest line, so the anchor the ring
        // would have carried is no longer the first thing in the sequence.
        val log = log(maxEntries = 1)
        log.pinnedEvent("started after %s", 7)
        log.event("ordinary")

        val lines = log.boundedSnapshot(1_000, 1_000)
        assertTrue(lines.toString(), isMarker(lines.first()))
        assertTrue(lines.toString(), "started after 7" in lines[1])
    }

    @Test
    fun `a pinned line the tail can only clamp is kept whole by the reserve`() {
        // Once, and in full. Both halves matter and each was got wrong in turn
        // (Codex, PR #7): an identity set built from the clamped tail rather
        // than from the originals writes the line in both sections, and
        // filtering it out on the strength of the clamp leaves the log holding
        // a truncated start-up line while the reserve meant to protect exactly
        // that line goes unspent.
        val log = log()
        log.pinnedEvent("started after %s", 7)

        // A recent budget too small for that single line, so the tail can only
        // clamp it -- and a pinned budget with ample room to carry it whole.
        val lines = log.boundedSnapshot(pinnedBudgetChars = 1_000, recentBudgetChars = 40)
        val events = lines.filterNot { isMarker(it) }
        assertEquals(events.toString(), 1, events.size)
        assertTrue(events.single(), "started after 7" in events.single())
        assertFalse(events.single(), events.single().endsWith("…(truncated)"))
    }

    @Test
    fun `an unpinned line the tail can only clamp is still kept clamped`() {
        // The yield above is for pinned lines only. An ordinary one has no
        // other section to go to, so clamping it stays right -- dropping it
        // would lose the freshest context entirely.
        val log = log()
        log.event("ordinary %s", 7)

        val lines = log.boundedSnapshot(pinnedBudgetChars = 1_000, recentBudgetChars = 40)
        val events = lines.filterNot { isMarker(it) }
        assertEquals(events.toString(), 1, events.size)
        assertTrue(events.single(), events.single().endsWith("…(truncated)"))
    }

    @Test
    fun `the anchors a trim will add are charged to the budget`() {
        // They are synthesized after the trim, so counted any other way they are
        // lines nobody budgeted for and the persisted file overruns the ceiling
        // it documents (Codex, PR #7).
        val log = log()
        repeat(50) { log.event("ordinary %s", it) }

        val budget = 300
        val rendered = log.boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = budget)
        assertTrue(rendered.toString(), rendered.any { isMarker(it) })
        assertTrue(
            "rendered ${rendered.sumOf { it.length + 1 }} chars against $budget",
            rendered.sumOf { it.length + 1 } <= budget,
        )
    }

    @Test
    fun `each section's anchors are charged to its own budget`() {
        val log = log(maxEntries = 4)
        log.pinnedEvent("started after %s", 7)
        repeat(6) { log.event("ordinary %s", it) }

        val pinnedBudget = 120
        val recentBudget = 200
        val rendered = log.boundedSnapshot(pinnedBudget, recentBudget)
        assertTrue(rendered.toString(), rendered.any { "started after 7" in it })
        assertTrue(
            "rendered ${rendered.sumOf { it.length + 1 }} chars",
            rendered.sumOf { it.length + 1 } <= pinnedBudget + recentBudget,
        )
    }

    @Test
    fun `the existing bounds keep their positional meaning`() {
        // Consumers compile this source from `@main` with nothing pinned, so a
        // parameter inserted among the existing ones rebinds every positional
        // argument after it — still compiling, now meaning something else
        // (Codex, PR #7). `maxPinnedEntries` therefore goes last, and this
        // pins that: read positionally, the second argument is still the entry
        // bound. Inserted second instead, it would bind 40 to the pinned bound
        // and 6 to the entry bound, truncating every line to six characters.
        val log = DebugLog(300, 40, 6, 5)
        log.event("a message comfortably longer than the entry bound allows")

        val entry = log.snapshot().single { !isMarker(it) }
        assertEquals(entry, 40, entry.removeSuffix("…(truncated)").length)
    }

    @Test
    fun `a pinned line is never dropped by a reserve too small to keep it`() {
        // The yield hands the tail's only line to the pinned section, so it
        // must first be true that the section will keep something. With a
        // reserve too small to hold anything the yield became a deletion --
        // the freshest event in neither section (Codex, PR #7).
        val log = log()
        log.pinnedEvent("started after %s", 7)

        val lines = log.boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = 40)
        val events = lines.filterNot { isMarker(it) }
        assertEquals(events.toString(), 1, events.size)
        // Clamped, because the recent budget is all there was -- but present.
        assertTrue(events.single(), events.single().endsWith("…(truncated)"))
    }

    @Test
    fun `a clamped line never strands half a code point`() {
        // The same rule the entry-length clamp already follows. A cut between
        // the surrogates of a non-BMP character corrupts it in the persisted
        // snapshot.
        val log = log()
        log.event("%s", safe("😀".repeat(20)))

        // Sweep the budgets around the pair boundaries rather than guessing one.
        for (budget in 30..80) {
            val text = log.boundedSnapshot(0, budget).joinToString("\n")
            text.forEachIndexed { i, c ->
                if (c.isHighSurrogate()) {
                    assertTrue(
                        "budget $budget: high surrogate at $i has no low surrogate after it",
                        i + 1 < text.length && text[i + 1].isLowSurrogate(),
                    )
                }
                if (c.isLowSurrogate()) {
                    assertTrue(
                        "budget $budget: low surrogate at $i has no high surrogate before it",
                        i > 0 && text[i - 1].isHighSurrogate(),
                    )
                }
            }
        }
    }

    @Test
    fun `a budget too small for the clamp's own marker keeps nothing`() {
        // The marker and the anchor are fixed costs, so there is no clamped
        // form at this size that fits -- and emitting one anyway overruns the
        // ceiling the budget is.
        val log = log()
        log.pinnedEvent("started after %s", 7)
        log.event("ordinary")

        for (budget in 0..12) {
            val lines = log.boundedSnapshot(pinnedBudgetChars = budget, recentBudgetChars = budget)
            assertEquals("budget $budget rendered $lines", emptyList<String>(), lines)
        }
    }

    @Test
    fun `a budget that fits only the marker keeps just that`() {
        // The boundary of the rule above: enough for the marker, its newline
        // and the anchor, and the clamped stub is written.
        val log = log()
        log.event("ordinary")
        val anchor = "${DebugLog.MARKER_LEVEL} timezone offset Z".length + 1

        val lines = log.boundedSnapshot(0, recentBudgetChars = anchor + "…(truncated)".length + 1)
        assertEquals(lines.toString(), listOf("…(truncated)"), lines.filterNot { isMarker(it) })
    }

    @Test
    fun `a trim that bites still leaves every timestamp anchored`() {
        // The ordering the design turns on. Anchor first and then trim, and the
        // trim takes the anchor off the front with the oldest lines -- leaving
        // a file of local timestamps with nothing saying which offset reads
        // them, which is the orphaning synthesized anchors exist to prevent.
        val log = log()
        repeat(50) { log.event("ordinary %s", it) }

        val lines = log.boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = 200)
        assertTrue(lines.toString(), lines.isNotEmpty())
        assertTrue(lines.toString(), isMarker(lines.first()))
        // The trim really did bite, so the assertion above is not vacuous.
        assertTrue(lines.toString(), lines.none { "ordinary 0 " in it })
    }

    @Test
    fun `a pinned line is kept by identity, not by matching the tail's text`() {
        // Two entries a second apart can render identically. Deciding by text
        // would read the tail's copy as this one and drop a line that is in
        // fact gone from the tail.
        val log = log(maxEntries = 2)
        log.pinnedEvent("same")
        log.event("same")
        log.event("filler")

        val events = log.boundedSnapshot(1_000, 1_000).filterNot { isMarker(it) }
        assertEquals(events.toString(), 3, events.size)
        assertEquals(events.toString(), 2, events.count { it.endsWith("same") })
    }
}
