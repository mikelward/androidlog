package app.mikelward.androidlog

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
 * carried or withheld on its own by its type. The two places data-shaped values
 * could still slip through are closed structurally — a throwable renders as
 * **types and stack frames only, never messages**, and a composite is rendered
 * through [LogSummary], which carries its own reduced form.
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
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 300
        const val DEFAULT_MAX_ENTRY_CHARS = 2_000
        const val DEFAULT_MAX_TRACE_FRAMES = 6
        const val DEFAULT_MAX_CAUSE_LINKS = 5

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

        /** The level character on a marker entry, as `D` and `W` are on the rest. */
        internal const val MARKER_LEVEL = 'I'
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

    /** One sink's place in the registry: the offset it has actually been anchored to. */
    private class Registration {
        var offset: ZoneOffset? = null
    }

    /**
     * Where each entry is mirrored once it is safely in the buffer — the
     * persisted file, logcat, anything the app adds. Each call is isolated, so
     * one sink's failure can't reach another or the caller.
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
                sinkAnchors.values.forEach { it.offset = null }
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
     * Registers a sink. It carries no anchor yet, so the next entry announces
     * the offset to it before that entry — no special case, and nothing to
     * retry separately if that announcement fails.
     */
    fun addSink(sink: Sink) {
        synchronized(buffer) { sinkAnchors.putIfAbsent(sink, Registration()) }
    }

    fun removeSink(sink: Sink) {
        synchronized(buffer) { sinkAnchors.remove(sink) }
    }

    /** Detaches every sink. For a test tearing down, and for nothing else. */
    fun clearSinks() {
        synchronized(buffer) { sinkAnchors.clear() }
    }

    /**
     * Records one line.
     *
     * [format] is a hard-coded format string — a source literal, never a built
     * value — with one `%s` per argument. That split is what makes the log safe
     * to mirror off the device later: the literal cannot name anything of the
     * user's, and each argument is carried or withheld on its own by
     * [logArgumentMayLeaveDevice]. The on-device line always renders every
     * argument in full.
     *
     * Interpolating into [format] defeats it. The type system cannot enforce
     * that, so it is a rule: pass values as arguments.
     */
    fun event(format: String, vararg args: Any?) = record('D', format, args, throwable = null)

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
    fun warning(format: String, vararg args: Any?) {
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
            failure(
                throwable,
                "$format [throwable passed to warning(); use failure()]",
                *args,
            )
            return
        }
        record('W', format, args, throwable = null)
    }

    /** A warning carrying the exception behind it. Same contract as [event]. */
    fun failure(throwable: Throwable, format: String, vararg args: Any?) =
        record('W', format, args, throwable)

    /**
     * The captured lines, oldest first, with an offset anchor before the oldest
     * retained run and before every offset change inside the window.
     *
     * Synthesized here rather than stored, so no amount of eviction can leave a
     * run of local timestamps without the offset that reads them. The anchors
     * carry **no timestamp**: each describes the run that follows it, and a
     * stored marker's own time would be either older than the window it now
     * heads or newer than the line beneath it.
     *
     * The sink stream is anchored differently, and deliberately -- see
     * [markerFor]. A sink is append-only, nothing is evicted from it, and a
     * marker written there at the moment the offset changed is both correct and
     * better carrying the time it happened.
     */
    fun snapshot(): List<String> = synchronized(buffer) {
        val out = ArrayList<String>(buffer.size + 2)
        var anchored: ZoneOffset? = null
        for (line in buffer) {
            val offset = line.offset
            if (offset != null && offset != anchored) {
                out += "$MARKER_LEVEL timezone offset $offset"
                anchored = offset
            }
            out += line.text
        }
        out
    }

    private fun record(level: Char, format: String, args: Array<out Any?>, throwable: Throwable?) {
        // Before anything else, including the enabled read: a sink recording
        // from inside its own `log()` is not supported, and the entry is
        // dropped rather than allowed to overtake the one being delivered. The
        // count is reported on the next ordinary entry -- see
        // [deliveringThread].
        if (deliveringThread === Thread.currentThread()) {
            // Reentrant: this thread is inside `deliver`, which holds it.
            synchronized(buffer) { droppedFromSink++ }
            return
        }
        // One read, so the enabled flag and the period it belongs to cannot
        // disagree. A disabled log costs exactly this volatile read on a
        // latency-critical path.
        val started = session
        if (!started.recording) return
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
        val entry = runCatching {
            render(now, level, formatLogMessage(format, args, redactSensitive = false), throwable)
        }.getOrElse { return }
        gate.read {
            // Still the same session? Anything else — a disable, a re-enable,
            // or both while this entry was rendering — means it began in a
            // period that is over, and it does not belong in this one.
            if (session !== started) return
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
                append(entry, now)
            }
        }
    }

    /**
     * Appends one rendered entry and hands it to every sink, under the caller's
     * hold on the buffer's monitor.
     *
     * The fan-out is inside the monitor rather than after it so a sink sees
     * lines in the order the buffer took them, and so a retried anchor still
     * precedes the first entry that sink sees.
     */
    private fun append(entry: String, at: ZonedDateTime?) {
        if (buffer.size >= maxEntries) buffer.removeFirst()
        buffer.addLast(Line(entry, at?.offset))
        deliver(entry, at)
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
        append(notice, at)
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
        val notice = runCatching {
            val sinks = if (failures == 1) "sink" else "sinks"
            render(
                at,
                'W',
                "$failures $sinks failed to clear a saved copy when recording " +
                    "was turned off",
                cause,
            )
        }.getOrNull() ?: return
        clearFailures = 0
        clearFailure = null
        append(notice, at)
    }

    private fun deliver(entry: String, at: ZonedDateTime?) {
        val offset = at?.offset
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
                if (offset != null && registration.offset != offset) {
                    val marker = "${TIMESTAMP.format(at)} $MARKER_LEVEL timezone offset $offset"
                    val anchored = runCatching { sink.log(marker) }.isSuccess
                    // Compared by identity, not membership: the callback may have
                    // unregistered this sink, or removed and re-added it, and a
                    // fresh registration is waiting for an anchor of its own.
                    // Writing this one's result into it would mark it anchored by a
                    // marker its destination never saw.
                    if (sinkAnchors[sink] !== registration) continue
                    if (anchored) registration.offset = offset
                }
                runCatching { sink.log(entry) }
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
    ): String {
        val timestamp = at?.let { runCatching { TIMESTAMP.format(it) }.getOrNull() }
            ?: "(no timestamp)"
        val entry = if (throwable == null) {
            "$timestamp $level $message"
        } else {
            "$timestamp $level $message\n${throwable.typesAndFrames().trimEnd()}"
        }
        if (entry.length <= maxEntryChars) return entry
        // Stepped back off a high surrogate, so the cut never splits a pair and
        // strands half a code point at the end of the line (Codex, PR #1). The
        // sibling cut in `LogcatSink` already respects code points; this one was
        // still counting UTF-16 units.
        val cut = if (entry[maxEntryChars - 1].isHighSurrogate()) maxEntryChars - 1 else maxEntryChars
        return entry.take(cut) + "…(truncated)"
    }

    /**
     * A cause chain as **types and frames only** — deliberately never
     * `getMessage()`, from any link.
     *
     * A platform exception quotes what it was given, and on the paths this log
     * exists for that is exactly what the floor bans: the number that was
     * dialed, the network that was joined, the package that failed to launch.
     * There is no scrubber here to catch it (AGENTS.md), so the message is not
     * read at all. The type names the failure and the frames locate it, which is
     * what a bug report needs.
     *
     * Guarded against a cyclic cause chain, and bounded per link and per chain
     * so one throw can't consume the whole entry budget.
     */
    private fun Throwable.typesAndFrames(): String = buildString {
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
            appendLine(prefix + throwable.javaClass.name)
            val frames = throwable.stackTrace
            val keep = minOf(maxTraceFrames, frames.size)
            for (i in 0 until keep) appendLine("\tat ${frames[i]}")
            if (frames.size > keep) appendLine("\t... ${frames.size - keep} more")
            current = runCatching { throwable.cause }.getOrNull()
            prefix = "Caused by: "
        }
    }
}
