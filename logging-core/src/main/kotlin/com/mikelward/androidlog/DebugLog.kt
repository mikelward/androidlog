package com.mikelward.androidlog

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The recording half of an on-device debug log: a bounded in-memory buffer of
 * coarse state and reasons, fanned out to whatever sinks the app registers.
 *
 * A **class**, not an object, though every consumer holds exactly one. Each app
 * declares its own singleton (`object SimmoLog : DebugLog()`), which keeps the
 * name in that app's vocabulary at the call sites and — the part that matters —
 * lets a test build a fresh instance instead of reaching for a `clearForTest`
 * back door on shared global state, which is what all three existing copies
 * had to grow.
 *
 * Recording never throws and never blocks: entries are built and appended in
 * memory, and sinks are isolated and expected to enqueue rather than write
 * inline. That is what makes it safe to call from a latency-critical path — a
 * call-redirection decision, a Quick Settings tile's arm path — and from inside
 * failure handling, where a second failure must not preempt the first one's
 * recovery.
 *
 * **The privacy floor is enforced by what this API accepts, not by scrubbing**
 * (see `LogValue.kt`): a hard-coded format string plus arguments, each argument
 * carried or withheld on its own by its type. A composite renders its elements
 * as arguments in their own right, so each is judged by the same rule, and a
 * throwable bound into a `%s` placeholder renders as its type alone — that one
 * holds in both directions, because it rests on never calling an unknown
 * `toString()` rather than on where a line is going.
 *
 * **The floor is a boundary, not an ingestion filter, and this class renders
 * both sides of it.** [snapshot], [pinnedSnapshot], [boundedSnapshot] and every
 * sink registered for [Destination.DEVICE] carry the full rendering: untagged
 * strings as they were passed, and each throwable's message with its type and
 * frames. That is deliberate — this log exists to be read by the person whose
 * device it is.
 *
 * A sink registered for [Destination.OFF_DEVICE] is handed the reduced
 * rendering instead, so a crash reporter's breadcrumbs or an automatic report
 * can be a sink rather than something the consumer assembles itself. What is
 * still the caller's job is everything **returned** from here: a snapshot is
 * the device's own text, and forwarding one as it stands is the mistake this
 * boundary exists to prevent. Build what leaves from a sink, or from
 * [formatLogMessage] with `leavingDevice = true` and [offDeviceTrace].
 */
open class DebugLog(
    /** Bounds the buffer; old entries fall off the front. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** Bounds one entry, so a pathological trace can't dominate the buffer. */
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
    /** How many stack frames each link of a cause chain keeps. */
    private val maxTraceFrames: Int = DEFAULT_MAX_TRACE_FRAMES,
    /** How many cause links a chain renders before eliding the rest. */
    private val maxCauseLinks: Int = DEFAULT_MAX_CAUSE_LINKS,
    /**
     * Wall-clock time for entries, injectable so tests can pin the format.
     *
     * Real timestamps rather than intervals: an inexact alarm landing outside a
     * Doze window cannot be reconstructed from intervals, and a user reports
     * what their own clock said, so entries are written in *local* time. The
     * year is dropped, since a log spans two runs at most.
     *
     * The offset is **not** on every line. It is emitted as its own marker
     * entry, once at the start and again whenever it changes (maintainer,
     * 2026-08-29) -- a DST transition or a flight, the two cases where a run of
     * local timestamps silently stops meaning what the line above it meant. On
     * every line it is 5 characters of noise per entry to answer a question
     * that changes twice a year.
     */
    private val readMillis: () -> Long = System::currentTimeMillis,
    /**
     * Bounds the pinned buffer — the lines [pinnedEvent] keeps past the ring's
     * eviction. Bounded anyway, and oldest-evicted like the ring: a device
     * where the pinned condition genuinely flaps writes one line per flip, and
     * the newest are the ones worth keeping.
     *
     * **Last, rather than beside the other bounds it belongs with.** Consumers
     * compile this source directly from `@main` with nothing pinned, so a
     * parameter inserted among the existing ones rebinds every positional
     * argument after it — `DebugLog(300, 100, 6)` would keep compiling while
     * silently truncating every entry to six characters (Codex, PR #7). A
     * signature that changes what a call *means* without changing whether it
     * compiles is the one shape this repository's no-pin model cannot absorb,
     * so new parameters go on the end and this comment says why the grouping
     * is broken.
     */
    private val maxPinnedEntries: Int = DEFAULT_MAX_PINNED_ENTRIES,
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 300
        const val DEFAULT_MAX_PINNED_ENTRIES = 32
        const val DEFAULT_MAX_ENTRY_CHARS = 2_000
        const val DEFAULT_MAX_TRACE_FRAMES = 6
        const val DEFAULT_MAX_CAUSE_LINKS = 5

        /**
         * How much of one throwable's message the device's own copy keeps.
         *
         * Not a constructor setting, unlike the frame and cause-link bounds:
         * those trade diagnostic depth against the entry budget and an app
         * might reasonably want a different trade. This one exists only so a
         * pathological message cannot spend the whole entry, and the frames it
         * would push out are the part nothing else can recover.
         */
        private const val MAX_MESSAGE_CHARS = 300

        /**
         * Stands in for an entry whose off-device rendering threw.
         *
         * Carries no argument of the app's, by construction — that is the
         * point, since the alternative on this path is the full rendering,
         * which may not cross the boundary.
         */
        private const val OFF_DEVICE_RENDER_FAILED = "(this entry could not be rendered for off-device use)"

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

        /**
         * The level character on a **synthesized** anchor — the one [anchored]
         * prepends to a snapshot, where there is no entry to borrow a level
         * from and nothing filters by severity.
         *
         * A *delivered* anchor takes the level of the entry it precedes
         * instead, so that a destination filtering by severity cannot drop it
         * while keeping what it explains. See [deliver].
         */
        internal const val MARKER_LEVEL = 'I'

        /**
         * Orders the level characters so a delivered anchor can tell when a
         * later entry outranks it. Anything unrecognized ranks as `D`, which
         * is the severity `LogcatSink` gives it — so an app's own level
         * behaves the same way here as it does there.
         */
        private fun severityRank(level: Char): Int = when (level) {
            'V' -> 0
            'I' -> 2
            'W' -> 3
            'E' -> 4
            else -> 1
        }
    }

    init {
        // A zero or negative bound made the first append call `removeFirst()`
        // on an empty deque, and that mutation sits outside the `runCatching`
        // around rendering -- so `event()` threw, against the never-throw
        // guarantee this class is built on (Codex, PR #1). Rejected at
        // construction because it is a programming error, not a runtime
        // condition: a log that cannot hold a line is not a log, and failing
        // where the mistake is beats failing on a call site's first event.
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        // Same reasoning as [maxEntries], and the same failure: a non-positive
        // bound sends the first pinned append into `removeFirst()` on an empty
        // deque, outside the `runCatching` around rendering.
        require(maxPinnedEntries > 0) {
            "maxPinnedEntries must be positive, was $maxPinnedEntries"
        }
        // Guarded for the same reason and found the same way. A negative bound
        // sends every entry into `take(-1)`, which throws inside the
        // `runCatching` around rendering -- so `record` returns quietly and the
        // log drops everything, forever, with nothing to say why (Codex, PR #1).
        // Silently logging nothing is the worst of the failure modes here.
        require(maxEntryChars > 0) { "maxEntryChars must be positive, was $maxEntryChars" }
        // Neither bound throws when it is out of range -- it quietly corrupts,
        // which is worse (Codex, PR #1). A negative `maxTraceFrames` makes the
        // kept count negative, so the entry claims more omitted frames than the
        // trace has; zero is fine and means "types, no frames".
        require(maxTraceFrames >= 0) { "maxTraceFrames must not be negative, was $maxTraceFrames" }
        // The chain's head counts as a link, so anything below one elides the
        // throwable before even its type is rendered -- leaving `failure()`
        // writing an entry that says causes were omitted without ever naming
        // the failure. The type is the floor of the diagnostic.
        require(maxCauseLinks >= 1) { "maxCauseLinks must be positive, was $maxCauseLinks" }
    }

    /**
     * One captured line, with the UTC offset its timestamp was rendered in.
     *
     * The offset is carried per line rather than the ring holding marker lines
     * of its own, and that is what makes anchoring correct by construction
     * instead of by patching. A marker stored *in* a bounded ring is subject to
     * eviction, and there is more than one way for eviction to orphan a run of
     * timestamps from the offset that reads them: the opening marker rolling
     * off the front, or a full buffer crossing an offset change so the new
     * marker's own append evicts the old one and leaves older entries ahead of
     * it (Codex, PR #1, rounds 3 and 4 -- two symptoms, one cause). Keeping the
     * offset on the line and synthesizing anchors in [snapshot] makes an
     * unanchored run unrepresentable.
     *
     * Null only when the clock could not be read, and such a line inherits
     * whatever run it sits in rather than falsely starting a new one.
     */
    private class Line(val text: String, val offset: ZoneOffset?)

    private val buffer = ArrayDeque<Line>(maxEntries)

    /**
     * The subset of [buffer]'s lines that must outlive its eviction; see
     * [pinnedEvent].
     *
     * Holds the **same [Line] instances**, so a pinned line still appears in
     * the ring for as long as the ring keeps it and this copy is what remains
     * afterwards. Sharing the instance is also what lets [boundedSnapshot]
     * decide by identity which pinned lines the ring's kept tail still carries,
     * rather than comparing rendered text — two entries a second apart can
     * render identically, and equality would then drop a line that is genuinely
     * missing from the tail.
     *
     * Guarded by the same monitor as [buffer], not one of its own, so a line
     * lands in both as a single step. With a lock each, two recorders could
     * interleave between the two appends and leave the pinned copy in a
     * different order from the ring's — a report whose start-up section reads
     * out of sequence (Codex on typelauncher #689).
     */
    private val pinnedBuffer = ArrayDeque<Line>(maxPinnedEntries)

    /**
     * Every registered sink, mapped to the offset it has actually been anchored
     * to — null until its first anchor lands.
     *
     * One structure, replacing a sink list, a per-log `announcedOffset`, a
     * special case in [addSink], and a retry set. Anchoring is per *destination*
     * and always was: a global "already announced" flag cannot describe a set of
     * streams that were attached at different times and can fail
     * independently. Three rounds of review found three consequences of
     * pretending otherwise — a late sink never anchored, a late sink whose
     * anchor threw never retried, and a marker that threw during an ordinary
     * fan-out silently advancing the flag anyway (Codex, PR #1). None of them
     * is expressible now: a sink is anchored when, and only when, an anchor was
     * delivered to it, and every entry re-checks.
     *
     * `IdentityHashMap`, so registration is by reference. `Sink` is a function
     * interface; two instances that happen to compare equal are still two
     * places lines are being sent, and equality-based storage would silently
     * drop the second.
     *
     * The value is a mutable [Registration] rather than the offset itself, so
     * one *registration* of a sink is distinguishable from the next. A sink
     * that removes and re-adds itself expects to be anchored again, and by
     * value alone the fresh null anchor is indistinguishable from the null the
     * fan-out already read -- so a marker delivered before the re-registration
     * would satisfy it, and the next entry would reach the sink's new
     * destination with no anchor at all (Codex, PR #1). Identity settles it:
     * the fan-out writes back only to the registration it read.
     *
     * Guarded by the buffer's monitor, like every other fan-out decision.
     */
    private val sinkAnchors = IdentityHashMap<Sink, Registration>()

    /**
     * One sink's place in the registry: the offset it has actually been
     * anchored to, and the highest severity that anchor went out at.
     *
     * The severity matters because a destination may filter by it — logcat
     * does, and handing the level over is what made that possible. An anchor
     * written once, at one level, is dropped by exactly the filter that keeps
     * the higher-severity entries it exists to explain, leaving their local
     * timestamps with no offset to read them against (Codex, PR #9). So a
     * later entry that outranks the anchor gets one of its own.
     */
    /**
     * How many registrations are [Destination.OFF_DEVICE], as a volatile read
     * for [record] to consult **before** it takes the buffer's monitor.
     *
     * Written only under that monitor, by [addSink] / [removeSink] /
     * [clearSinks], so it never disagrees with [sinkAnchors] for a reader that
     * holds it. Read without it, which is the whole point: the reduced
     * rendering walks the app's own arguments, and doing that under the
     * monitor is a lock inversion — a recorder would hold the monitor while
     * waiting on a collection's lock, and a thread holding that collection
     * while calling [event] would wait on the monitor (Codex, PR #26). The
     * device's rendering has always happened outside; this keeps the other one
     * there too.
     */
    @Volatile
    private var offDeviceSinks: Int = 0

    private class Registration(val destination: Destination) {
        var offset: ZoneOffset? = null

        /** Rank of the level the current [offset]'s anchor was last sent at. */
        var anchoredRank: Int = Int.MIN_VALUE
    }

    /**
     * Which of the two renderings a sink is given.
     *
     * The library's floor is a **boundary**: the device's own copy of a log is
     * whole, and the reduction applies to what leaves. There are exactly two
     * sides to that boundary, so there are exactly two values here, and a sink
     * declares which side it is on rather than deciding for itself what its
     * text should say.
     *
     * That the set is closed is the point, not an incidental detail. A sink
     * that could describe its destination freely would be choosing its own
     * rendering, which is what the floor forbids; a sink that picks one of two
     * fixed classes is answering a question this class then acts on.
     */
    enum class Destination {
        /**
         * Stays on this device: the persisted file, logcat, a screen, a
         * report the user opens and reads before choosing where to send it.
         *
         * Gets the full rendering — every argument as written and each
         * throwable's message with it — because that is what makes a log worth
         * reading, and because nothing here reaches anyone without the user
         * putting it there.
         */
        DEVICE,

        /**
         * Leaves without the user in the loop for each item: a crash
         * reporter's breadcrumbs, an analytics event, an automatic report.
         *
         * Gets the reduced rendering, in which an untagged `String` and a
         * [sensitive] value both render as the placeholder and a throwable
         * keeps its types and frames but no message from any link. Widening a
         * single value for this destination is [safe]'s job, at the call site,
         * where the person who knows what the value is can say so.
         *
         * **This is not consent, and it is not a gate.** Whether the app may
         * send anything at all is the app's decision — a settings toggle, a
         * Play Data Safety declaration — and it belongs in the sink, which can
         * simply do nothing when the answer is no. This enum only decides what
         * the text says if it goes.
         */
        OFF_DEVICE,
    }

    /**
     * Where each entry is mirrored once it is safely in the buffer — the
     * persisted file, logcat, anything the app adds. Each call is isolated, so
     * one sink's failure can't reach another or the caller.
     *
     * **A sink says which kind of destination it is, and this class decides
     * what that gets** — see [Destination] and [addSink]. A
     * [Destination.DEVICE] sink is handed the rendering this device keeps:
     * every argument in full and each throwable's message with it. A
     * [Destination.OFF_DEVICE] sink is handed the reduced rendering, the same
     * one [formatLogMessage] produces with `leavingDevice = true`.
     *
     * An earlier version of this contract said the on-device form was all a
     * sink ever got, that forwarding it to a crash reporter was forbidden, and
     * that nothing here could enforce that (Codex, PR #24). The first two were
     * right and the third was the problem: a rule a class states and cannot
     * check is one a call site breaks silently. Declaring the destination is
     * what turns it into something the library applies rather than warns about.
     *
     * **This is not the per-sink rendering the floor forbids.** That rule was
     * against a sink *choosing* how a value is written, which puts the
     * reduction at the mercy of each one remembering to ask. Here a sink says
     * what it **is**, not how to render; there are exactly two destination
     * classes and a sink cannot invent a third; and which rendering each gets
     * is decided once, here.
     *
     * **A sink enqueues; it does not write inline.** Delivery happens under the
     * buffer's monitor, so a sink that blocks here blocks every other recorder,
     * and on the paths this library exists for that is not affordable.
     *
     * Two things follow from that, and both are prohibitions rather than
     * behavior this class can make work:
     *
     * - **Never call [setRecording] from inside [log].** That is a
     *   read-to-write upgrade on a lock that does not allow one, and it
     *   deadlocks.
     * - **Never record — [event], [warning], [failure] — from inside [log].**
     *   The entry is dropped, and counted, and the count is reported on the
     *   next ordinary entry (maintainer, 2026-08-30). A sink honoring the
     *   enqueue contract loses nothing by this: its own work, and any failure
     *   of it, happens later on its own thread, where recording is not
     *   reentrant and works normally.
     */
    fun interface Sink {
        fun log(line: String)

        /**
         * The same line, with the level character it was recorded at.
         *
         * Defaulted to [log] so a sink written as a lambda stays a lambda and
         * every existing one keeps working. A sink that maps onto a destination
         * with its own severities — logcat — overrides this instead of parsing
         * the level back out of the rendered line, which is a second and weaker
         * copy of a decision already made here.
         *
         * `V`, `D`, `I`, `W`, `E` for the five recording levels, and `I` for the
         * offset markers this class synthesizes.
         */
        fun log(line: String, level: Char) = log(line)

        /**
         * The buffer was emptied because recording was turned off.
         *
         * A sink that keeps a durable copy has to act on this, or "off" means
         * off only in memory: [setRecording] discards what was collected, but a
         * sink's own copy survives, and on the next launch it is still there to
         * be read and shared — diagnostics from before the opt-out, kept by the
         * one component that outlives the buffer (Codex, PR #4). A sink that
         * keeps nothing durable (logcat) has nothing to do and takes the
         * default.
         *
         * The enqueue contract and both prohibitions above apply here exactly as
         * they do to [log]: this is called under the same monitor, and recording
         * from inside it is dropped the same way.
         */
        fun onCleared() {}
    }

    /**
     * A recording session: whether recording is on, and — by its identity — a
     * distinct enabled period.
     *
     * One object read once, rather than a boolean and a counter read
     * separately. Two volatile fields cannot be read as one consistent state,
     * and every arrangement of them leaked a different way: with the counter
     * read second, a recorder could pair a pre-disable `true` with a
     * post-re-enable counter; with it read first, a recorder could pair a
     * counter captured while *off* with a `true` seen after a re-enable, since
     * re-enabling advanced nothing. Three rounds of review found three
     * arrangements of the same fault (Codex, PR #1). A single reference makes
     * the pair unrepresentable rather than defended.
     *
     * Identity is the whole mechanism: [setRecording] publishes a **new**
     * `Session` on every real transition and on nothing else, so an entry that
     * began in one and finds another in place has crossed a boundary and is
     * dropped, whichever direction it crossed.
     */
    private class Session(val recording: Boolean)

    @Volatile
    private var session = Session(recording = true)

    /**
     * The thread currently inside [deliver], if any — how a record made from
     * inside a sink's own callback is recognized.
     *
     * A sink calling [event] from [Sink.log] re-enters both the gate's read
     * lock and the buffer's monitor, since both are reentrant, so the nested
     * entry used to append and fan out **completely** before the outer fan-out
     * reached its next sink: a later sink saw the two in the opposite order to
     * [snapshot], and a sink that logged on every entry recursed until the
     * stack gave out (Codex, PR #1).
     *
     * Recording from inside [Sink.log] is not supported (maintainer,
     * 2026-08-30), and the reason it costs a correct sink nothing is the
     * contract [Sink] already states: sinks *enqueue*. A sink that honors that
     * also fails later, on its own thread, where recording is not reentrant and
     * works normally. A queue that made the nested call work would only serve a
     * sink that was already writing inline, and would need a cap of its own,
     * which is this same drop one step further along.
     *
     * Compared by identity against the calling thread, so another thread merely
     * *blocked* on the monitor is unaffected — it is not delivering, it is
     * waiting to record.
     */
    @Volatile
    private var deliveringThread: Thread? = null

    /**
     * Nested records dropped since the last one that was reported.
     *
     * *Never fail silently*: the entries are gone, but that they existed is
     * not. Guarded by the buffer's monitor, which the dropping thread already
     * holds — it is inside [deliver].
     */
    private var droppedFromSink = 0

    /**
     * Excludes a disable from the whole of a record — the append *and* the
     * delivery to every sink — rather than from the append alone.
     *
     * Recorders take the read side, so they never block each other and the
     * common path is uncontended. `setRecording(false)` takes the write side,
     * so it waits for every in-flight delivery to finish before it returns.
     * Without that, a recorder could pause between appending and delivering
     * while a disable ran to completion, and then hand its entry to a
     * persistent sink after the user had opted out (Codex, PR #1). "Off" has
     * to mean off, and it cannot mean off only for entries that had not
     * started yet.
     *
     * The buffer keeps its own monitor inside the read side, since the read
     * lock is shared and two recorders would otherwise mutate the deque at
     * once.
     *
     * **A sink must not call [setRecording] from inside [Sink.log].** That is a
     * read-to-write upgrade, which this lock does not allow and which would
     * deadlock. Sinks enqueue; nothing about that needs the gate.
     */
    private val gate = ReentrantReadWriteLock()

    /**
     * Applies the setting. Disabling also **empties the buffer**: entries
     * already collected are exactly what a re-enable in the same process would
     * otherwise write to disk. Every sink is told ([Sink.onCleared]), so one
     * holding a durable copy can discard it too — otherwise "off" would mean
     * off only in memory.
     */
    fun setRecording(enabled: Boolean) {
        gate.write {
            // A no-op when nothing changes. Publishing a fresh session for a
            // redundant call would discard every in-flight entry for a setting
            // that did not move.
            if (enabled == session.recording) return@write
            session = Session(enabled)
            if (!enabled) synchronized(buffer) {
                buffer.clear()
                // The whole point of the pinned copy is that it outlives
                // eviction, so nothing else in this class would ever remove
                // these lines. Left behind, "off" would keep recorded content
                // in memory and hand it to the next persisted file — the exact
                // leak the opt-out exists to close.
                pinnedBuffer.clear()
                // The pending drop report belongs to the session being
                // discarded. Left standing, the first entry of the next one
                // would open with a warning about entries lost before the user
                // turned recording off — a stale diagnostic pointed at the
                // wrong session is worse than none (Codex, PR #3).
                droppedFromSink = 0
                // The marker went with the clear, so the next entry has to
                // announce the offset again rather than assume it still stands.
                // Every sink re-announces after a re-enable rather than
                // assuming the offset it last saw still stands.
                sinkAnchors.values.forEach {
                    it.offset = null
                    it.anchoredRank = Int.MIN_VALUE
                }
                // Last, so a sink acting on this sees the emptied buffer rather
                // than the one being discarded.
                notifyCleared()
            }
        }
    }

    /**
     * Whether entries are currently being recorded.
     *
     * For callers deciding whether to do the *work* that would produce an entry,
     * not merely whether the entry would be kept. Gathering diagnostics the gate
     * will discard is wasted effort, and on an opted-out install it means the
     * app still collected what the user asked it not to, even if nothing was
     * written.
     */
    val isRecording: Boolean
        get() = session.recording

    /**
     * Registers a sink for [destination], which decides which of the two
     * renderings it is given — see [Destination]. Defaulted to
     * [Destination.DEVICE], so every existing registration is unchanged and a
     * sink only reaches the reduced form by asking for it.
     *
     * It carries no anchor yet, so the next entry announces the offset to it
     * before that entry — no special case, and nothing to retry separately if
     * that announcement fails.
     *
     * Registering the same sink twice keeps the first registration, its
     * destination included: `putIfAbsent` is what makes a re-`addSink` from
     * inside a callback harmless, and silently re-pointing a live sink at the
     * other destination would be the more surprising of the two behaviors.
     * [removeSink] then [addSink] moves one deliberately.
     */
    fun addSink(sink: Sink, destination: Destination) {
        synchronized(buffer) {
            sinkAnchors.putIfAbsent(sink, Registration(destination))
            recountOffDeviceSinks()
        }
    }

    /**
     * Registers an on-device sink — [addSink] with [Destination.DEVICE].
     *
     * A separate overload rather than a default argument, and that is forced
     * rather than stylistic: a defaulted second parameter makes `destination`
     * the last one, so Kotlin binds a trailing lambda to *it* and every
     * `addSink { ... }` in every consumer stops compiling. Reordering to put
     * the sink last breaks the other form, `addSink(sink)` with a named
     * variable, since a positional argument cannot skip a defaulted parameter.
     * Two overloads keep both, which is what "every existing call site is
     * unchanged" has to mean.
     */
    fun addSink(sink: Sink) {
        addSink(sink, Destination.DEVICE)
    }

    fun removeSink(sink: Sink) {
        synchronized(buffer) {
            sinkAnchors.remove(sink)
            recountOffDeviceSinks()
        }
    }

    /** Detaches every sink. For a test tearing down, and for nothing else. */
    fun clearSinks() {
        synchronized(buffer) {
            sinkAnchors.clear()
            recountOffDeviceSinks()
        }
    }

    /**
     * Recomputes [offDeviceSinks] from the registry. Counted rather than
     * incremented, because `putIfAbsent` may or may not have inserted and
     * `remove` may or may not have found anything — a delta would drift.
     * Called only under the buffer's monitor.
     */
    private fun recountOffDeviceSinks() {
        offDeviceSinks = sinkAnchors.values.count { it.destination == Destination.OFF_DEVICE }
    }

    /**
     * Records one line.
     *
     * [format] is a hard-coded format string — a source literal, never a built
     * value — with one `%s` per argument. That split is what makes the log safe
     * to leave the device at all: the literal cannot name anything of the
     * user's, and each argument is carried or withheld on its own by
     * [logArgumentMayLeaveDevice].
     *
     * **What is recorded here is the device's own copy, and it is whole.**
     * Every argument renders as it was passed, so the buffer, the log screen,
     * every [Destination.DEVICE] sink and anything persisted from them read
     * the same full text. The floor applies at the boundary instead: an
     * argument [logArgumentMayLeaveDevice] withholds renders as
     * [REDACTED_PLACEHOLDER] on the far side of it — in a
     * [Destination.OFF_DEVICE] sink's copy, and in [formatLogMessage] with
     * `leavingDevice = true`, which is what a caller building something that
     * *leaves* renders from itself.
     *
     * So `safe(...)` is not what keeps a value here — nothing withholds it
     * here. It is what carries a value off the device as well, for a call site
     * that has already reduced it; and `sensitive(...)` is the other way
     * round, keeping the full form on the device and withholding it from
     * anything that leaves.
     *
     * Interpolating into [format] defeats it. The type system cannot enforce
     * that, so it is a rule: pass values as arguments.
     *
     * **Answers whether the entry actually landed**, which is false when
     * recording is off, when the call came from inside a sink's own delivery,
     * and when rendering it failed. Almost every call site ignores it. The ones
     * that do not are failure reports that must not count themselves said when
     * nobody received them: a sink that reports a repeating failure once per
     * spell has to latch that spell on a line that landed, and asking
     * [isRecording] first cannot answer it — the gate can close between the
     * question and the entry, and it says nothing about the other two drops
     * (Codex, PR #4).
     */
    fun event(format: String, vararg args: Any?): Boolean =
        record('D', format, args, throwable = null)

    /**
     * Records one line and keeps a second reference to it, so it survives the
     * ring evicting it. Same contract as [event] in every other respect —
     * including the privacy floor, which is the same rule applied at the same
     * point.
     *
     * For the handful of lines that say how *this run* began: what ended the
     * previous process, when the package was last updated, which permissions
     * were held at start. They are written once each, and the ring holds a few
     * hundred entries of a log a busy device fills in well under two hours — so
     * by the time a user notices something and shares a report, the lines
     * explaining how the run started are routinely gone, which is exactly what
     * they were added to answer (Codex on typelauncher #689).
     *
     * **Reserve it.** Anything written more than a few times a run belongs in
     * [event], where eviction is the correct behavior: the pinned buffer is
     * bounded too ([maxPinnedEntries]), so a chatty caller evicts the start-up
     * lines from the one place that was keeping them.
     */
    fun pinnedEvent(format: String, vararg args: Any?): Boolean =
        record('D', format, args, throwable = null, pinned = true)

    /**
     * A warning with no exception behind it. Same contract as [event].
     *
     * A warning that *does* have an exception goes to [failure]. The throwable
     * is a separate parameter on a separately-named function on purpose: an
     * overload taking it alongside `vararg args` would silently bind it as a
     * formatting argument, so it would render through `toString()` — restoring
     * the exception message this class exists to exclude — and be findable only
     * in production. One passed here is rerouted rather than rendered.
     */
    fun warning(format: String, vararg args: Any?): Boolean {
        // Looked for *under* any tag: `warning("...", safe(e))` is the same
        // misuse as `warning("...", e)`, and matching on the wrapper's own type
        // missed it -- the line then carried the exception's type with neither
        // its frames nor the marker saying the call site had used the wrong
        // function (Codex, PR #1).
        val throwable = args.firstNotNullOfOrNull { untaggedLogValue(it) as? Throwable }
        if (throwable != null) {
            // Every argument is passed through unchanged, the throwable
            // included. Removing it shifted every later value one placeholder
            // left, so `warning("cause %s after %s", e, 3)` rendered
            // `cause 3 after %s` -- and `filterNot` dropped every *other*
            // occurrence of the same instance too (Codex, PR #1). Left in
            // place it renders as its type, which is what an argument-position
            // throwable renders as anyway; `failure` adds the frames below.
            return failure(
                throwable,
                "$format [throwable passed to warning(); use failure()]",
                *args,
            )
        }
        return record('W', format, args, throwable = null)
    }

    /** A warning carrying the exception behind it. Same contract as [event]. */
    fun failure(throwable: Throwable, format: String, vararg args: Any?): Boolean =
        record('W', format, args, throwable)

    /**
     * Below [event]: high-frequency detail worth having in the buffer but not
     * worth reading past. Same contract as [event] in every other respect.
     *
     * The five levels exist because a consumer had five and the level reaches
     * logcat, where it is what a developer filters on. Three would have flattened
     * `V`, `D` and `I` into one and silently cost that (clothescast, 278 call
     * sites).
     */
    fun verbose(format: String, vararg args: Any?): Boolean =
        record('V', format, args, throwable = null)

    /** Above [event]: worth reading on a first pass. Same contract as [event]. */
    fun info(format: String, vararg args: Any?): Boolean =
        record('I', format, args, throwable = null)

    /**
     * Above [warning]: something is broken rather than merely surprising.
     *
     * A throwable passed as an argument is rerouted to the overload below, for
     * [warning]'s reason — bound as a formatting argument it would render
     * through a `toString()` this library did not write.
     */
    fun error(format: String, vararg args: Any?): Boolean {
        val throwable = args.firstNotNullOfOrNull { untaggedLogValue(it) as? Throwable }
        if (throwable != null) {
            return error(
                throwable,
                "$format [throwable passed to error(); use error(throwable, ...)]",
                *args,
            )
        }
        return record('E', format, args, throwable = null)
    }

    /** An error carrying the exception behind it. Same contract as [event]. */
    fun error(throwable: Throwable, format: String, vararg args: Any?): Boolean =
        record('E', format, args, throwable)

    /**
     * The captured lines, oldest first, with an offset anchor before the oldest
     * retained run and before every offset change inside the window.
     *
     * Synthesized rather than stored ([anchored]), so no amount of eviction can
     * leave a run of local timestamps without the offset that reads them. The
     * anchors carry **no timestamp**: each describes the run that follows it,
     * and a stored marker's own time would be either older than the window it
     * now heads or newer than the line beneath it.
     *
     * The ring only. A pinned line still in the ring appears here like any
     * other; one the ring has evicted does not — [pinnedSnapshot] and
     * [boundedSnapshot] are what still carry it.
     *
     * The sink stream is anchored differently, and deliberately -- see
     * [markerFor]. A sink is append-only, nothing is evicted from it, and a
     * marker written there at the moment the offset changed is both correct and
     * better carrying the time it happened.
     */
    fun snapshot(): List<String> = synchronized(buffer) { anchored(buffer) }

    /**
     * The pinned lines ([pinnedEvent]), oldest first, anchored as [snapshot] is.
     *
     * Anchored over its own run rather than sharing the ring's: the two are
     * rendered as separate sections, so an anchor emitted for one says nothing
     * about the other.
     */
    fun pinnedSnapshot(): List<String> = synchronized(buffer) { anchored(pinnedBuffer) }

    /**
     * Both buffers as one chronological, anchored sequence, each trimmed to its
     * own character budget — the pinned lines the ring's kept tail no longer
     * carries, then that tail.
     *
     * **The reserve is the point.** A persisted log is a *tail*: it keeps the
     * newest and drops the oldest, which is the opposite of what the start-up
     * lines need. Given one shared budget they are the first thing to go, and
     * the report loses exactly the evidence pinning exists to keep (Codex on
     * typelauncher #689). [pinnedBudgetChars] is held back for them, so the two
     * sections cannot compete.
     *
     * **One call rather than three, because the three steps do not commute.**
     * Which pinned lines to prepend can only be decided once the ring's tail
     * has been trimmed — a line the ring still holds may be outside the kept
     * tail — and the anchors can only be synthesized once both trims are done,
     * since trimming from the front of an already-anchored list takes the
     * anchor with it and leaves every local timestamp beneath it unreadable.
     * Doing them in the wrong order is silent, so the order is not left to a
     * caller to remember.
     *
     * Prepending is chronological, not a guess: a pinned line is missing from
     * the kept tail only because newer lines pushed it out — of the ring, or of
     * the budget — and both drop oldest-first, so every such line is older than
     * everything in the tail. Both buffers are read under one monitor, so
     * nothing can land between the two reads and be classified as older than a
     * line it is in fact newer than.
     */
    fun boundedSnapshot(pinnedBudgetChars: Int, recentBudgetChars: Int): List<String> =
        synchronized(buffer) {
            val lines = buffer.toList()
            val trimmed = anchoredTail(lines, recentBudgetChars)
            // The originals the trim *stands for*, not the trim itself: its
            // oldest line may have been clamped, and a clamp is a new [Line]
            // that an identity set would fail to match against the pinned
            // buffer's copy (Codex, PR #7). [anchoredTail] returns a suffix and
            // only ever replaces this one element, so the last `trimmed.size`
            // originals are exactly what it kept.
            val originals = lines.takeLast(trimmed.size)
            // A clamped line that is *also* pinned is handed to the pinned
            // section rather than kept here cut short. Retaining it both ways
            // writes it twice; retaining only the clamp leaves the persisted
            // log holding a truncated start-up line while the reserve that
            // exists to protect exactly that line sits unspent (Codex, PR #7).
            // The clamp fires only when nothing else fit, so `trimmed` is a
            // single element and giving it up empties the tail — the recent
            // budget had one line to spend it on and that line has a better
            // home.
            val clamped = trimmed.isNotEmpty() && trimmed.first() !== originals.first()
            // And only when the reserve will actually keep it. A pinned budget
            // too small to hold anything makes the yield a deletion: the tail
            // empties and the pinned trim then returns nothing, so the freshest
            // event is in neither section (Codex, PR #7). Asking the trim
            // itself, rather than comparing budgets here, keeps the two from
            // disagreeing about what fits.
            val yielded = clamped &&
                pinnedBuffer.any { it === originals.first() } &&
                anchoredTail(listOf(originals.first()), pinnedBudgetChars).isNotEmpty()
            val tail = if (yielded) emptyList() else trimmed
            // Identity, not equality: two entries a second apart can render to
            // the same text, and dropping a pinned line because the tail holds
            // an equal-looking one would lose a line that is genuinely gone
            // from it. The same instance is in both buffers by construction.
            val alreadyKept = Collections.newSetFromMap(IdentityHashMap<Line, Boolean>())
            if (!yielded) alreadyKept += originals
            val pinned =
                anchoredTail(pinnedBuffer.filterNot { it in alreadyKept }, pinnedBudgetChars)
            anchored(pinned + tail)
        }

    /**
     * The newest of [lines] whose *rendering* fits [budgetChars] — the lines
     * themselves and the offset anchors [anchored] will synthesize for them.
     *
     * Anchor-aware because the anchors are added last. Counted any other way
     * they are lines nobody budgeted for, and the persisted file overruns the
     * ceiling it documents by one marker per offset run — unbounded in
     * principle, since a device that keeps changing offset writes one per
     * change (Codex, PR #7). Counting them here is what lets the synthesis stay
     * after the trim, which is what stops a trim orphaning the timestamps
     * beneath it.
     *
     * Returns a **suffix** of [lines] with only its first element ever
     * replaced, by [clamp]; [boundedSnapshot] relies on that to recover which
     * originals the result stands for.
     *
     * Conservative across two calls: each assumes it opens an anchor run of its
     * own, so composing two results can only over-reserve. The alternative —
     * letting the second know what the first ended on — would trade a few
     * characters for a coupling between the sections.
     */
    private fun anchoredTail(lines: List<Line>, budgetChars: Int): List<Line> {
        val kept = ArrayDeque<Line>()
        var used = 0
        // The offset of the earliest anchor in `kept` so far. Prepending a line
        // carrying that same offset moves the anchor rather than adding one, so
        // it costs nothing; any other non-null offset opens a new run.
        var earliest: ZoneOffset? = null
        for (line in lines.asReversed()) {
            val offset = line.offset
            val anchor = if (offset != null && offset != earliest) anchorCost(offset) else 0
            val cost = line.text.length + 1 + anchor
            if (used + cost > budgetChars) {
                if (kept.isNotEmpty()) break
                // The newest line alone overruns: kept clamped rather than
                // dropped, for [boundedLogTail]'s reason, and with room left for
                // the anchor it still needs.
                val room = budgetChars - anchor - TRUNCATION_MARKER.length - 1
                // Unless the budget cannot hold even that. The marker and the
                // anchor are fixed costs, so clamping to a negative payload
                // still emits them and overruns the ceiling the budget *is* —
                // a section asked for zero characters answering with thirty
                // (Codex, PR #7). Nothing is the honest answer: there is no
                // truthful clamped form at this size.
                if (room < 0) break
                kept.addFirst(clamp(line, room))
                break
            }
            kept.addFirst(line)
            used += cost
            if (offset != null) earliest = offset
        }
        return kept.toList()
    }

    /** What one synthesized anchor costs the budget, newline included. */
    private fun anchorCost(offset: ZoneOffset): Int = marker(offset).length + 1

    private fun marker(offset: ZoneOffset): String = "$MARKER_LEVEL timezone offset $offset"

    /** Cuts one line down to [room] characters, marking that it was cut. */
    private fun clamp(line: Line, room: Int): Line =
        Line(line.text.take(codePointCut(line.text, room)) + TRUNCATION_MARKER, line.offset)

    /**
     * [lines] rendered oldest-first, with an offset anchor before the oldest
     * line and before every offset change within them.
     *
     * Synthesized here rather than stored, so no amount of eviction or trimming
     * can leave a run of local timestamps without the offset that reads them.
     */
    private fun anchored(lines: Collection<Line>): List<String> {
        val out = ArrayList<String>(lines.size + 2)
        var anchor: ZoneOffset? = null
        for (line in lines) {
            val offset = line.offset
            if (offset != null && offset != anchor) {
                out += marker(offset)
                anchor = offset
            }
            out += line.text
        }
        return out
    }

    private fun record(
        level: Char,
        format: String,
        args: Array<out Any?>,
        throwable: Throwable?,
        pinned: Boolean = false,
    ): Boolean {
        // Before anything else, including the enabled read: a sink recording
        // from inside its own `log()` is not supported, and the entry is
        // dropped rather than allowed to overtake the one being delivered. The
        // count is reported on the next ordinary entry -- see
        // [deliveringThread].
        if (deliveringThread === Thread.currentThread()) {
            // Reentrant: this thread is inside `deliver`, which holds it.
            synchronized(buffer) { droppedFromSink++ }
            return false
        }
        // One read, so the enabled flag and the period it belongs to cannot
        // disagree. A disabled log costs exactly this volatile read on a
        // latency-critical path.
        val started = session
        if (!started.recording) return false
        // One clock reading for the whole entry, taken here and used for both
        // the line and any marker that precedes it. Read twice, the two could
        // straddle a transition and the marker would announce the new offset
        // over a timestamp rendered under the old one -- anchoring the very
        // line it exists to anchor, wrongly (Codex, PR #1).
        val now = runCatching {
            Instant.ofEpochMilli(readMillis()).atZone(ZoneId.systemDefault())
        }.getOrNull()
        // Contained whole rather than per step: recording runs on paths where
        // nothing it does may escape -- a lost log line is the accepted cost, a
        // dropped call or a stuck snooze is not. Built before the gate is
        // taken, so rendering never holds it.
        //
        // **Rendered for this device, which is where this log lives.** The
        // buffer, `snapshot()`, every on-device sink and the persisted file
        // are all on this side, and nothing among them leaves without the
        // user opening a report and consenting. So they carry the full
        // rendering: the strings that make a log worth reading -- a component,
        // a label, a package -- and a throwable's message with it. An
        // off-device sink is delivered to from the same entry but gets the
        // reduced form, rendered separately in `deliver`.
        //
        // This reverses *the floor is applied at ingestion, so there is one
        // rendering* (maintainer, 2026-08-31). That rule reduced the buffer
        // itself, on the reasoning that a value the type rule withholds should
        // exist in full nowhere in the process. What it cost was the log: an
        // untagged `String` is every identifier *and* every diagnostic, so
        // reducing at ingestion left the on-device log reading `•••` where the
        // answer was supposed to be. The rule was aimed at what *leaves*, and
        // there was one rendering to hang it on; with two, it applies where it
        // was always pointed -- see [formatLogMessage] with `leavingDevice =
        // true`, and [offDeviceTrace].
        //
        // The floor that does not move is the app's: a full number, an ICCID,
        // a coordinate is reduced *before* this sees it, and no rendering here
        // can put one back.
        val entry = runCatching {
            render(now, level, formatLogMessage(format, args, leavingDevice = false), throwable)
        }.getOrElse { return false }
        // The other side of the boundary, rendered here or not at all.
        //
        // **Here, specifically: outside the buffer's monitor**, beside the
        // device's own rendering, because this walks the app's arguments and
        // doing that under the monitor inverts a lock order (Codex, PR #26) --
        // a recorder would hold the monitor while waiting on a collection's
        // lock, and a thread holding that collection while calling `event()`
        // would wait on the monitor. It is also the same rule `Sink` states
        // for a sink: work under this monitor blocks every other recorder.
        //
        // Rendered only when an off-device sink is actually registered. Most
        // installs register none, and an entry nobody reads should not cost a
        // second pass over its arguments on a latency-critical path.
        //
        // From the *original* arguments rather than anything derived from
        // `entry`: text already rendered cannot be un-rendered, which is why
        // this is built here rather than computed from what the buffer holds.
        //
        // `null` when no off-device sink was registered at this moment. A sink
        // that registers between here and the fan-out gets the notice for this
        // one entry rather than a rendering taken under the monitor -- the same
        // shape as the anchor it is also still waiting for, and it fails toward
        // saying less.
        val offDevice: String? = if (offDeviceSinks == 0) {
            null
        } else {
            runCatching {
                render(
                    now,
                    level,
                    formatLogMessage(format, args, leavingDevice = true),
                    throwable,
                    leavingDevice = true,
                )
            }.getOrNull()
        }
        gate.read {
            // Still the same session? Anything else — a disable, a re-enable,
            // or both while this entry was rendering — means it began in a
            // period that is over, and it does not belong in this one.
            if (session !== started) return false
            // Append and fan-out under the *same* monitor, so a sink sees lines
            // in the order the buffer took them. Released between the two, the
            // shared read lock let a second recorder append and deliver while
            // the first was still between its own two steps, so a persistent
            // sink could order entries differently from `snapshot()` -- and a
            // marker could land after the entry it anchors (Codex, PR #1).
            //
            // This is what the "sinks enqueue, they do not write inline"
            // contract on [Sink] is for: a sink that blocks here now blocks
            // other recorders, and on this library's paths that is not
            // affordable.
            synchronized(buffer) {
                // Ahead of the entry, so the loss is visible at the point in
                // the log where it happened rather than at the end. The opt-out
                // failure goes first: it is about something that happened
                // before this session opened.
                reportClearFailures(now)
                reportDroppedFromSink(now)
                append(entry, now, level, pinned, offDevice)
            }
        }
        return true
    }

    /**
     * Appends one rendered entry and hands it to every sink, under the caller's
     * hold on the buffer's monitor.
     *
     * The fan-out is inside the monitor rather than after it so a sink sees
     * lines in the order the buffer took them, and so a retried anchor still
     * precedes the first entry that sink sees.
     */
    private fun append(
        entry: String,
        at: ZonedDateTime?,
        level: Char,
        pinned: Boolean = false,
        // Defaulted to the entry itself, which is correct **only** for an
        // entry that carries no throwable and no argument of the app's — the
        // notices this class synthesizes over counts and offsets, where the
        // two renderings really are the same string.
        //
        // A caller passing a throwable must pass this too. That is not advice:
        // `render` writes each link's message on the device's side, and a
        // message is third-party text. The clear-failure notice below was
        // exactly this mistake — library-authored words wrapped around a
        // sink's own exception (Codex, PR #26).
        //
        // Already rendered rather than a lambda, so nothing renders under the
        // buffer's monitor — see the call in [record]. `null` means an
        // off-device sink gets the notice instead.
        offDevice: String? = entry,
    ) {
        val line = Line(entry, at?.offset)
        if (buffer.size >= maxEntries) buffer.removeFirst()
        buffer.addLast(line)
        // The same instance, in the same step under the same monitor — see
        // [pinnedBuffer] for why both of those matter.
        if (pinned) {
            if (pinnedBuffer.size >= maxPinnedEntries) pinnedBuffer.removeFirst()
            pinnedBuffer.addLast(line)
        }
        deliver(entry, at, level, offDevice)
    }


    /**
     * Writes one line for the entries dropped since the last report, if any.
     *
     * The count is cleared **before** the line is delivered, so a sink that
     * records from inside this very delivery is counted toward the next report
     * rather than lost. A line that could not be rendered leaves the count
     * standing to be reported next time, since a lost report is the silence
     * this exists to prevent.
     */
    private fun reportDroppedFromSink(at: ZonedDateTime?) {
        val dropped = droppedFromSink
        if (dropped == 0) return
        val notice = runCatching {
            val entries = if (dropped == 1) "entry" else "entries"
            render(
                at,
                'W',
                "$dropped $entries dropped: a sink recorded from inside log(), " +
                    "which is not supported",
                throwable = null,
            )
        }.getOrNull() ?: return
        droppedFromSink = 0
        // At `maxEntries == 1` this notice is evicted by the entry behind it,
        // and that is what a one-slot ring means rather than a fault in the
        // report: the ring holds the newest line, and nothing else was ever
        // going to survive there. The report still reaches every sink, which is
        // append-only, so that is where it keeps (Codex, PR #3). Holding the
        // count back instead would mean it never surfaced at that bound at all,
        // since every entry evicts the last.
        append(notice, at, 'W')
    }

    /**
     * Hands one entry to every sink, anchoring any that is not already on the
     * current offset.
     *
     * Called under the buffer's monitor, which is what keeps a sink's lines in
     * the order the buffer took them.
     *
     * A sink's anchor is recorded **only when the delivery succeeded**, so a
     * sink that throws on its marker is simply still unanchored and is offered
     * one again with the next entry. That is the whole retry mechanism.
     *
     * The entry goes out even when its anchor failed. Withholding it would
     * trade a header for the diagnostic itself, and an unanchored line still
     * says what happened; the anchor arrives on the next entry.
     *
     * The in-memory ring anchors itself instead, from the offset carried on
     * each [Line] — see [snapshot]. A sink is append-only and nothing is
     * evicted from it, so an inline marker carrying the time it happened is
     * right there and wrong in the ring.
     */
    /**
     * Tells every sink the buffer was emptied. Under the same delivering-thread
     * marker as [deliver], so a sink that records from inside [Sink.onCleared]
     * is dropped and counted rather than appending to the buffer the disable
     * just cleared. Each call is isolated, as delivery's are.
     */
    private fun notifyCleared() {
        deliveringThread = Thread.currentThread()
        try {
            for (sink in sinkAnchors.keys.toList()) {
                runCatching { sink.onCleared() }
                    .onFailure { failure ->
                        // Held, not said here: recording is off by definition at
                        // this point, so a line recorded now is dropped by the
                        // gate. And it has to be said somewhere — a sink that
                        // could not clear may still be holding exactly what the
                        // opt-out was meant to remove, and nothing else in the
                        // system knows (Codex, PR #4).
                        clearFailures++
                        if (clearFailure == null) clearFailure = failure
                    }
            }
        } finally {
            deliveringThread = null
        }
    }

    /**
     * Sinks whose [Sink.onCleared] threw, and the first throwable among them,
     * awaiting the next entry that can carry the notice.
     *
     * Unlike [droppedFromSink] this deliberately **survives the disable that
     * produced it**. That report was about entries lost from a session being
     * discarded, so it is stale in the next one; this one is about a durable
     * copy that outlives every session, and is still true whenever recording
     * comes back.
     */
    private var clearFailures = 0
    private var clearFailure: Throwable? = null

    /**
     * Writes one line for the sinks that could not clear, if any.
     *
     * Cleared before the line is delivered and left standing when it could not
     * be rendered, for [reportDroppedFromSink]'s reasons.
     */
    private fun reportClearFailures(at: ZonedDateTime?) {
        val failures = clearFailures
        if (failures == 0) return
        val cause = clearFailure
        val sinks = if (failures == 1) "sink" else "sinks"
        val message = "$failures $sinks failed to clear a saved copy when recording " +
            "was turned off"
        val notice = runCatching { render(at, 'W', message, cause) }.getOrNull() ?: return
        clearFailures = 0
        clearFailure = null
        // The one notice this class writes that carries a throwable, and so the
        // one that needs a reduced form of its own (Codex, PR #26). The message
        // is this library's own words over a count, but [cause] is a *sink's*
        // exception, and a third party's message can quote anything it was
        // given. Left on the default, the full rendering — every link's
        // message included — went to an off-device sink.
        append(
            notice,
            at,
            'W',
            offDevice = runCatching {
                render(at, 'W', message, cause, leavingDevice = true)
            }.getOrNull(),
        )
    }

    private fun deliver(
        entry: String,
        at: ZonedDateTime?,
        level: Char,
        offDevice: String? = entry,
    ) {
        val offset = at?.offset
        // Already rendered by the caller, outside this monitor. `null` covers
        // both a rendering that threw and an off-device sink that registered
        // after the caller decided none was listening.
        //
        // Never `entry` as a fallback: that is the full form, and substituting
        // it here would put it on the far side of the boundary — the one
        // mistake this whole mechanism exists to make unavailable. A fixed
        // notice instead, so the sink sees that something happened at this
        // point in the log and the loss is visible rather than silent.
        val reduced = offDevice ?: "$level $OFF_DEVICE_RENDER_FAILED"
        // Cleared in a `finally`: a sink is isolated by `runCatching` below, so
        // nothing here should escape, but leaving this set would silently drop
        // every later entry on this thread.
        deliveringThread = Thread.currentThread()
        try {
            // Iterated over a snapshot of the keys, not the live map. A sink is
            // free to call `addSink`, `removeSink` or `clearSinks` from inside
            // `log()`, and a structural change during iteration throws
            // `ConcurrentModificationException` out of the iterator's `next()` --
            // which is outside the `runCatching` that contains the callback, and so
            // escapes `event()` entirely, against the never-throw guarantee (Codex,
            // PR #1). Snapshotting also means the set of destinations for one entry
            // is decided once, rather than shifting underneath it.
            for (sink in sinkAnchors.keys.toList()) {
                // The registration this entry is going out under. Absent means the
                // sink unregistered earlier in this same fan-out, so it gets
                // nothing more.
                val registration = sinkAnchors[sink] ?: continue
                // The anchor goes out at this entry's own level, and again
                // whenever a later entry outranks the last one sent. A
                // severity filter keeps a line only above its threshold, so
                // anchoring once at a fixed level would let `Tag:W` drop the
                // offset while keeping the warnings that need it. Re-anchoring
                // on the running maximum means every entry a filter retains
                // was preceded by an anchor that same filter retained -- and
                // costs nothing on the common path, where the offset never
                // changes and no anchor is written at all.
                val rank = severityRank(level)
                val changed = registration.offset != offset
                if (offset != null && (changed || rank > registration.anchoredRank)) {
                    val marker = "${TIMESTAMP.format(at)} $level timezone offset $offset"
                    val anchored = runCatching { sink.log(marker, level) }.isSuccess
                    // Compared by identity, not membership: the callback may have
                    // unregistered this sink, or removed and re-added it, and a
                    // fresh registration is waiting for an anchor of its own.
                    // Writing this one's result into it would mark it anchored by a
                    // marker its destination never saw.
                    if (sinkAnchors[sink] !== registration) continue
                    if (anchored) {
                        registration.offset = offset
                        // The new maximum either way: on a change the count
                        // restarts here, and otherwise this rank is the one
                        // that just beat it.
                        registration.anchoredRank = rank
                    }
                }
                // The rendering this sink's destination may have. Decided
                // here, from what it registered as, rather than by the sink
                // itself.
                val text = if (registration.destination == Destination.DEVICE) entry else reduced
                runCatching { sink.log(text, level) }
            }
        } finally {
            deliveringThread = null
        }
    }

    private fun render(
        at: ZonedDateTime?,
        level: Char,
        message: String,
        throwable: Throwable?,
        // Applies to the throwable only. [message] arrives already rendered
        // for its side of the boundary, and the caller passes the two
        // consistently — a full message with a full trace, or a reduced one
        // with types and frames alone. Splitting them would produce a line
        // that is reduced in one half and not the other, which is neither
        // rendering.
        leavingDevice: Boolean = false,
    ): String {
        val timestamp = at?.let { runCatching { TIMESTAMP.format(it) }.getOrNull() }
            ?: "(no timestamp)"
        val entry = if (throwable == null) {
            "$timestamp $level $message"
        } else {
            "$timestamp $level $message\n${throwable.typesAndFrames(leavingDevice).trimEnd()}"
        }
        if (entry.length <= maxEntryChars) return entry
        // Stepped back off a high surrogate, so the cut never splits a pair and
        // strands half a code point at the end of the line (Codex, PR #1). The
        // sibling cut in `LogcatSink` already respects code points; this one was
        // still counting UTF-16 units.
        return entry.take(codePointCut(entry, maxEntryChars)) + TRUNCATION_MARKER
    }

    /**
     * [throwable] as types and frames, with no message from any link — the one
     * form of it that may leave the device.
     *
     * The public half of the split [typesAndFrames] describes. What this log
     * records for the device itself carries each link's message; a consumer
     * building something that leaves — a crash-reporter breadcrumb, an
     * automatic report — asks for this instead, and gets the reduced form
     * without having to know how to reduce it. The counterpart for a formatted
     * message is [formatLogMessage] with `leavingDevice = true`.
     *
     * Bounded per link and per chain by this log's own settings, like a
     * recorded entry, but not truncated to `maxEntryChars`: the caller knows
     * what its destination will take.
     */
    fun offDeviceTrace(throwable: Throwable): String =
        throwable.typesAndFrames(leavingDevice = true).trimEnd()

    /**
     * A cause chain as types and frames — and, on the device's own copy, each
     * link's message.
     *
     * **Off the device the message is never read, from any link.** A platform
     * exception quotes what it was given, and on the paths this log exists for
     * that is exactly what the floor bans: the number that was dialed, the
     * network that was joined, the package that failed to launch. There is no
     * scrubber here to catch it (AGENTS.md), and there cannot usefully be one —
     * the message's content and its sensitivity are both unknown to this code,
     * so any partial answer would be a guess. The type names the failure and
     * the frames locate it, which is what an automatic report may carry.
     *
     * **On the device it is read in full**, because the same argument runs the
     * other way there: dropping it costs `ActivityNotFoundException`'s intent
     * and `NameNotFoundException`'s package, which is the whole of what those
     * failures are diagnosed from, and nothing here leaves without the user
     * opening a report and consenting (maintainer, 2026-08-31). Whole or
     * nothing, chosen by destination, is what lets this avoid classifying
     * message content it cannot classify.
     *
     * Whether an off-device message could be opted into per call site — where
     * the type is known and its message is vocabulary — is recorded in
     * `TODO.md` rather than built.
     *
     * Walks the cause chain and names what each link suppressed (see
     * [appendSuppressed]) -- a close failure is on neither, and is lost by a
     * renderer that reads only causes.
     *
     * Guarded against a cyclic cause chain, and bounded per link and per chain
     * so one throw can't consume the whole entry budget.
     */
    private fun Throwable.typesAndFrames(leavingDevice: Boolean): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var prefix = ""
        var links = 0
        var current: Throwable? = this@typesAndFrames
        while (true) {
            val throwable = current ?: break
            if (!seen.add(throwable)) break
            if (links++ >= maxCauseLinks) {
                appendLine("\t... more causes elided")
                break
            }
            appendLine(prefix + throwable.javaClass.name + messageSuffix(throwable, leavingDevice))
            val frames = throwable.stackTrace
            val keep = minOf(maxTraceFrames, frames.size)
            for (i in 0 until keep) appendLine("\tat ${frames[i]}")
            if (frames.size > keep) appendLine("\t... ${frames.size - keep} more")
            appendSuppressed(throwable, leavingDevice)
            current = runCatching { throwable.cause }.getOrNull()
            prefix = "Caused by: "
        }
    }

    /**
     * A throwable's message, ready to append after its type — or nothing at all
     * when this rendering is leaving the device.
     *
     * Read through `runCatching` because `getMessage()` is overridable and runs
     * arbitrary code on the recording path; a throw here would lose the entry
     * that was being written about a failure, which is the worst moment for it.
     * An absent or blank message renders as nothing rather than a bare colon.
     */
    private fun messageSuffix(throwable: Throwable, leavingDevice: Boolean): String {
        if (leavingDevice) return ""
        val message = runCatching { throwable.message }.getOrNull() ?: return ""
        // Bounded **before** flattening rather than after (Codex, PR #24).
        // Flattening first built a whole copy of a message that might be
        // megabytes, on the recording path, only to throw all but a few hundred
        // characters of it away -- paying in full the allocation the bound
        // exists to prevent. `length` and `take` are both cheap, so nothing
        // beyond this prefix is ever copied.
        val overlong = message.length > MAX_MESSAGE_CHARS
        val head = if (overlong) message.take(MAX_MESSAGE_CHARS + 1) else message
        // Flattened, because a message is the one part of this rendering whose
        // content nothing here chose. A newline in it would otherwise open a
        // line indistinguishable from the ones this builds -- `Caused by: `
        // and `\tat ` are only text -- so a message could describe a failure
        // that never happened, in a log someone pastes into a bug report.
        //
        // Flattening never grows a string: a line's `trim` only removes, and
        // one separator stands in for the newline it replaces. So the cut below
        // can only shorten what is already at most `MAX_MESSAGE_CHARS + 1`, and
        // a message that is mostly newlines simply contributes less than the
        // bound rather than being read further to fill it.
        val kept = head.lineSequence().joinToString(" ") { it.trim() }.trim()
        // A blank message renders as nothing rather than a bare colon -- but a
        // long one whose *prefix* is all whitespace still says so, because
        // content was read and dropped and a silent drop is the one thing this
        // library does not do.
        if (kept.isEmpty()) return if (overlong) ": $TRUNCATION_MARKER" else ""
        // The bound itself, for the same reason the frames and the cause chain
        // have one: a message long enough to reach this would otherwise push
        // the frames out, and the frames are the half of a failure line that
        // cannot be recovered from anywhere else. The marker is driven by
        // `overlong` -- what was dropped is raw message, whether or not the
        // flattened form still reaches the bound.
        if (!overlong) return ": $kept"
        return ": " + kept.take(codePointCut(kept, MAX_MESSAGE_CHARS)) + TRUNCATION_MARKER
    }

    /**
     * Names each exception suppressed by [throwable] — type only, no frames.
     *
     * A `use { }` or try-with-resources close failure arrives here and nowhere
     * else: it is not the thrown exception and it is not on the cause chain, so
     * a renderer that walks only causes drops it entirely. That was the case
     * before this: a write that failed *and* whose close then failed logged the
     * write and said nothing about the close. clothescast's own logger kept a
     * one-line summary for exactly that reason, and the migration onto this
     * library would have lost it.
     *
     * Type only, deliberately. A suppressed exception is secondary by
     * definition -- the reader wants to know a close failed and what kind, not
     * to spend the entry budget on its stack -- and the floor is unchanged
     * either way, since a type names a failure and never the user.
     *
     * Bounded by [maxCauseLinks], the same budget the cause chain gets, because
     * it answers the same question: how many linked throwables may one entry
     * name. Costs nothing on the common path, where nothing is suppressed.
     */
    private fun StringBuilder.appendSuppressed(throwable: Throwable, leavingDevice: Boolean) {
        // `suppressed` allocates a copy on each call and is overridable, so it
        // is read once and guarded like `cause` is.
        val suppressed = runCatching { throwable.suppressed }.getOrNull() ?: return
        val keep = minOf(maxCauseLinks, suppressed.size)
        for (i in 0 until keep) {
            val link = suppressed[i]
            appendLine("\tSuppressed: ${link.javaClass.name}${messageSuffix(link, leavingDevice)}")
        }
        if (suppressed.size > keep) {
            appendLine("\t... ${suppressed.size - keep} more suppressed")
        }
    }
}
