package com.mikelward.androidlog.android

import android.content.Context
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.boundedLogTail
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Current run's persisted log; renamed to a [PREVIOUS_PREFIX] file at the next start.
 *
 * Named for this library rather than `debug.log`, which is what two of the
 * consumers' own sinks already write to their own cache directories. Sharing
 * the name would have made this reader pick up files the *old* implementation
 * left there on the first launch after a migration -- and those were written
 * under the previous rule, which rendered every argument in full, so the first
 * report would have disclosed exactly what the ingestion floor now withholds
 * (Codex, PR #4). A reader only ever surfaces what it wrote.
 *
 * Each app's migration deletes its own legacy files; the names are per-app and
 * this library cannot know them (see `TODO.md`). Nothing outside this
 * repository depends on these names yet, which is why the namespace is settled
 * now rather than after the first migration, when it could not be.
 */
private const val CURRENT_FILE = "androidlog.log"

/** Prefix for prior runs' logs, one file per run, surfaced then deleted by the report. */
private const val PREVIOUS_PREFIX = "androidlog-prev-"

/** Suffix for a prior run that ended gracefully or by a silent kill (no crash). */
private const val PREVIOUS_PLAIN_SUFFIX = ".log"

/**
 * Suffix marking a prior run that ended in an *uncaught exception* — a real
 * crash, as opposed to a routine kill (OS reclaim, force-stop, app update). Only
 * these raise the post-crash banner, so it is never shown for an ordinary
 * process death. Both kinds of prior run stay readable and shareable
 * ([readPreviousRun]); the suffix only gates the *banner*.
 */
private const val PREVIOUS_CRASH_SUFFIX = ".crash.log"

/**
 * Companion to [CURRENT_FILE], written by the uncaught-exception handler and
 * consumed at the next start. Its presence there is the one reliable in-process
 * signal that this run crashed rather than exited or was killed; a graceful run
 * never creates it. Kept out of the prior-run listing by not matching
 * [PREVIOUS_PREFIX].
 */
private const val CRASH_MARKER_FILE = "$CURRENT_FILE.crash"

/** How many unshared prior runs to keep — older ones are dropped at startup. */
private const val MAX_PREVIOUS_RUNS = 5

/**
 * Bound on the persisted content. The in-memory buffer is already count-bounded,
 * so mirroring it caps each file too; this is a belt-and-suspenders ceiling.
 */
private const val PERSIST_BUDGET_CHARS = 150_000

/**
 * The slice of [PERSIST_BUDGET_CHARS] held back for the pinned start-up lines,
 * so they are never the part that gets trimmed.
 *
 * The persisted file is a *tail* — newest kept, oldest dropped — which is the
 * opposite of what those lines need; see [DebugLog.boundedSnapshot], which is
 * where the reserve is actually applied. Sized for a few dozen short lines and
 * nothing more: every character here is one the recent log does not get, and
 * the recent log is what a report is usually read for.
 */
private const val PINNED_PERSIST_BUDGET_CHARS = 20_000

/**
 * How long the crash handler waits for the final flush (queued behind any
 * in-flight write) before chaining on. Long enough to land the crash snapshot on
 * healthy storage, short enough that a stalled disk never delays the next
 * handler in the chain or process termination.
 */
private const val CRASH_WRITE_TIMEOUT_MS = 250L

/**
 * Debounce window for continuous-mirror writes. Every persisted write is a full
 * replacement of the (bounded) buffer, and an app can log on frequent events —
 * key-down/up while typing, state transitions — so writing per entry would
 * rewrite the whole file dozens of times a second. Coalescing over this window
 * bounds that to a couple of writes a second while still guaranteeing a trailing
 * write. A silent kill can lose at most this much tail from the mirror; a crash
 * cannot, because the crash handler flushes explicitly.
 */
private const val WRITE_DEBOUNCE_MS = 500L

/**
 * Persists a [DebugLog] to app-private files so it survives the process ending —
 * a crash *or* a silent kill (a process can be killed with no visible UI and no
 * uncaught exception, so a crash-only handler would miss it).
 *
 * The current run mirrors the in-memory ring buffer to [CURRENT_FILE]; at the
 * next start that file is renamed aside under a unique [PREVIOUS_PREFIX] name,
 * so every unshared prior run is kept — several cold starts can happen before
 * the user shares — and none is lost to a later boring run. A run that ended in
 * an *uncaught exception* is marked (via [CRASH_MARKER_FILE]) and rotated to a
 * distinct crash-suffixed name, so the next start can tell a real crash apart
 * from a routine kill and raise a post-crash banner only for the former.
 *
 * **Everything touching the filesystem runs on a single daemon worker** — the
 * startup rotation, the continuous mirroring, the crash flush, the reads. So
 * nothing blocks the caller on disk, and the cold-start path stays clear. The
 * worker is FIFO, so the rotation enqueued in [start] always runs before this
 * run's writes, its reads, or the crash flush, preserving "rotate before we
 * clobber the prior run" without a lock.
 *
 * This sink never records into [log] from inside [log] — [DebugLog] drops and
 * counts a nested record, so a self-diagnostic written there would be thrown
 * away. Its own failures are reported from the worker thread instead, which is
 * an ordinary caller as far as recording is concerned.
 *
 * ### Provenance
 *
 * Merged from the two hand-maintained copies this library replaces, each of
 * which carried review fixes the other never got — which is the drift the
 * library exists to end. From `simmo`: the unlistable-directory distinction,
 * the derived crash state, and once-per-spell failure reporting. From
 * `typelauncher`: the write debounce, the atomic replace, and the bounded tail.
 */
/**
 * One report's worth of prior runs: the text to send, and the files it was
 * built from.
 *
 * Handed back by [DebugFileSink.readPreviousRun] and consumed by
 * [DebugFileSink.clearPreviousRun], so a report deletes exactly what it
 * contained. That pairing is the point: the files used to live in one slot on
 * the sink, which meant a clear consumed whatever the *latest* read had
 * surfaced rather than what the caller clearing was shown — so two overlapping
 * report flows could have the earlier one destroy a run only the later one had
 * read, and whose own report might still fail (Codex, PR #4).
 *
 * A caller that never received a handle therefore cannot delete anything, which
 * is what retired the ticket bookkeeping that used to answer the same question
 * indirectly.
 */
class PreviousRun internal constructor(
    /** The report, oldest run first and bounded to the newest content. */
    val text: String,
    /**
     * What to delete once it has been sent. Mutable and worker-owned: a
     * dismissal can rename a file while this report is in flight, and the clear
     * has to follow it or miss.
     */
    internal val files: MutableList<File>,
    /**
     * Whether [text] is the **whole** of every prior run this handle consumes.
     *
     * False three ways, each leaving [text] a partial account:
     * a run could not be read and was skipped; the directory could not be
     * listed at all; or the persisted budget trimmed lines away. The first two
     * leave those files in place for a later read. The third does not — the
     * trim drops whole lines off the front, so an older run can lose every one
     * of its lines to a verbose newer one while its file stays in [files] and
     * [DebugFileSink.clearPreviousRun] deletes it (Codex, PR #20).
     *
     * Exposed because a caller cannot work it out. The skipped run is not in
     * [files] and contributes nothing to [text], so a handle missing an
     * unreadable crash is indistinguishable from one that is simply the
     * ordinary run beside it — and a report built from the second, treated as
     * if it were the first, can consume a crash it never carried (Codex,
     * snoozemo#153). The text does carry a human-readable notice for whoever
     * reads the report; this is the same fact in a form a caller can branch on
     * without matching on a sentence.
     */
    val complete: Boolean,
)

class DebugFileSink internal constructor(
    private val log: DebugLog,
    dirProvider: () -> File,
    /**
     * Debounce for the continuous mirror. 0 in tests, so a logged line persists
     * as soon as the worker drains and assertions stay deterministic;
     * [WRITE_DEBOUNCE_MS] in production.
     */
    private val writeDebounceMs: Long,
    /**
     * Run on the worker on the crash path, before the snapshot, in its own
     * `runCatching` — a hook for whatever last-moment state the app wants in the
     * crash log (a cache counter, a queue depth). Optional in the strict sense:
     * the fatal being handled may be the very [OutOfMemoryError] that reading
     * that state would raise again, and it must not take the crash marker or the
     * snapshot down with it.
     */
    private val onCrash: () -> Unit,
    /**
     * Names the file a rotation moves the previous run into, under
     * [PREVIOUS_PREFIX] and before the suffix. `System.nanoTime()` in
     * production, where it only has to be unique within the directory; pinned
     * by a test that needs the destination to be a path it can arrange.
     */
    private val rotationName: () -> String,
    /**
     * Builds the single worker every file operation runs on: a prestarted
     * daemon thread, scheduled so a debounced write can be enqueued with a delay
     * while a zero-delay task (rotation, crash flush, share read) still runs
     * ahead of it.
     *
     * A seam like the four above, and for the same reason: several guards here
     * answer for an enqueue the worker *refuses*, which nothing else in a test
     * can arrange. Production always gets the default.
     */
    workerFactory: () -> ScheduledExecutorService = {
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "androidlog-file-sink").apply { isDaemon = true }
        }.apply { prestartCoreThread() }
    },
) : DebugLog.Sink {

    /**
     * Production: `cacheDir` is resolved lazily, so the (possibly dir-creating)
     * I/O of `getCacheDir()` runs on the worker at first use rather than on the
     * main thread inside this constructor during cold start.
     */
    @JvmOverloads
    constructor(
        log: DebugLog,
        context: Context,
        onCrash: () -> Unit = {},
    ) : this(
        log,
        { context.applicationContext.cacheDir },
        WRITE_DEBOUNCE_MS,
        onCrash,
        { System.nanoTime().toString() },
    )

    /** Tests and direct use: an already-resolved directory, writes un-debounced. */
    constructor(log: DebugLog, dir: File) :
        this(log, { dir }, 0L, {}, { System.nanoTime().toString() })

    // Resolved on first access, which is always inside a worker task (every file
    // operation runs there), so cacheDir resolution never touches the caller's
    // thread.
    private val dir: File by lazy(dirProvider)

    private val current get() = File(dir, CURRENT_FILE)
    private val temp get() = File(dir, "$CURRENT_FILE.tmp")
    private val crashMarker get() = File(dir, CRASH_MARKER_FILE)

    // Single-threaded, so every file operation is serialized without an explicit
    // lock. See [workerFactory] for the rest, and for why it is injectable.
    private val worker: ScheduledExecutorService = workerFactory()

    /**
     * The outstanding debounced write, as an identity token, or null when none
     * is pending. A token rather than a boolean because three parties reach for
     * it — the scheduled task, [awaitIdle], and [log]'s own handler for a
     * scheduling the worker refused — and a bare flag cannot tell whose window
     * it is clearing. Each of them clears by compare-and-set against the token
     * it is acting for, so a slow party can only ever clear its own attempt and
     * never a newer one that has taken the slot since (Codex, PR #18).
     */
    private val writeToken = AtomicReference<Any?>(null)

    // ---------------------------------------------------------------- listing

    /**
     * Whether the marker says the just-ended run ended in an uncaught exception.
     *
     * Content, not mere existence, so [consumeCrashMarker] has somewhere to fall
     * back to when the file cannot be removed. A marker that is not a plain file
     * says nothing — it is not something this class wrote. Null is the third
     * answer: the record could not be read, which the caller must not spend.
     */
    /**
     * Whether `debug.log` is there, or null when that could not be read.
     *
     * `File.exists()` gives the same false for "not there" and for a stat that
     * failed, and false here means *nothing to rotate* — so a failed lookup let
     * startup consume the marker and enable mirroring over a previous run still
     * sitting in the file, which the next snapshot then destroys (Codex, PR #4).
     * Read once, and read the way [markerSaysCrashed] is.
     */
    private fun currentRunPresent(): Boolean? = presenceOf(current)

    /**
     * Whether [file] is there, or null when that could not be read.
     *
     * The shared form of the read above: `NoSuchFileException` is a real answer
     * and anything else is unknown, so no caller has to spend a `false` that was
     * only ever a failed lookup.
     */
    private fun presenceOf(file: File): Boolean? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        true
    }.getOrElse { if (it is NoSuchFileException) false else null }

    private fun markerSaysCrashed(): Boolean? = runCatching {
        // Read through attributes rather than `File.isFile`, because that API
        // cannot tell "absent" from "could not look" -- both answer false, and
        // false is *permanent* here: the rotation renames the run under the
        // plain suffix and no later start can put the classification back
        // (Codex, PR #4). Attributes throw instead, and `NoSuchFileException`
        // is a real answer: no marker, so this run did not crash.
        val attributes = Files.readAttributes(crashMarker.toPath(), BasicFileAttributes::class.java)
        attributes.isRegularFile && attributes.size() > 0L
    }.getOrElse { if (it is NoSuchFileException) false else null }

    /**
     * Gets rid of a file's contents, answering whether they are gone.
     *
     * Deletion first, then truncation, because the two need different
     * permissions — removing a file needs write on the *directory*, emptying one
     * needs write on the *file* — so one can be refused where the other is not.
     * Every caller here cares about the **contents** rather than the directory
     * entry, so an emptied file counts: nothing empty is ever offered as a prior
     * run or as a crash.
     *
     * Not a secure erase, and it does not claim to be one — on flash storage
     * nothing at this level can promise that. It is the difference between
     * content still readable through this class's own API and content that is
     * not.
     *
     * Every one of these results used to be discarded, in three places, and each
     * had the same shape of consequence: a marker that survived mislabeled the
     * next run, a purge that failed left the opt-out's entries to be shared, and
     * a delete that failed re-sent a report the user had already sent (Codex,
     * PR #4).
     */
    private fun discardContents(file: File): Boolean {
        // `File.exists()` answers false both for a file that is not there and
        // for a stat that failed, and false used to mean "already gone" — so a
        // failed lookup reported a successful discard for a file still holding
        // the entries an opt-out was meant to remove (Codex, PR #4). The same
        // conflation the crash record had, one call down.
        //
        // `deleteIfExists` separates them: false only for genuine absence, which
        // is success here since there is nothing left to discard, and a throw for
        // everything else — including a refusal, which falls through to the
        // truncation below.
        if (runCatching { Files.deleteIfExists(file.toPath()) }.isSuccess) return true
        // Write permission on the file rather than on its directory, so this can
        // land where the delete was refused.
        return runCatching { file.writeText(""); true }.getOrDefault(false)
    }

    /**
     * Consumes the marker so it cannot mislabel a later run. Both failing is
     * reported and nothing more, because nothing in this process can undo it —
     * the mislabel would happen in the next one.
     */
    private fun consumeCrashMarker(): Boolean {
        if (discardContents(crashMarker)) return true
        log.warning("Crash marker could not be consumed, so the next start may misread this run")
        return false
    }

    /**
     * The previous run's log where a failed rotation left it, or null.
     *
     * When [moveAside] cannot move `debug.log` out of the way, mirroring stands
     * down — so that file holds the *previous* run and nothing else, and it is a
     * prior run in every sense that matters. Treating it as one everywhere is
     * what keeps a preserved crash reachable: it raises the banner and rides a
     * report during this launch rather than waiting for a rotation that may
     * never succeed, and an opt-out leaves it alone like any other prior run
     * (Codex, PR #4).
     */
    private fun retainedPreviousRun(): File? =
        // Its presence, not just the flag: a share can consume the retained run
        // while the stand-down goes on (below), and a file that is gone is not a
        // prior run to list, read or spare.
        //
        // **Unknown counts as retained.** The rotation defers on a presence it
        // could not read, and asking `File.exists()` here answered false for that
        // very file — so the stand-down's own re-check found nothing to protect,
        // resumed, and let the next write destroy the run it was deferring for
        // (Codex, PR #4). The flag is read first, so the attribute lookup only
        // happens while mirroring is already stood down.
        if (mirroringStoodDown && currentRunPresent() != false) current else null

    /**
     * Whether [file] is a prior run that ended in an uncaught exception, or null
     * when that could not be read.
     *
     * A real file with something in it, not merely a matching name: anything
     * else in the directory is not something this class wrote, and an emptied
     * one is a log whose contents have already been discarded — raising the
     * banner for either offers a crash log that is not there. The listing stays
     * unfiltered, so the read path still sees such an entry and still leaves it
     * alone rather than destroying it.
     *
     * Null rather than false when the entry's own metadata could not be read
     * (Codex, PR #4). Answering false there *lowers a banner that is already
     * up*, and while the value is recomputed at every point the record changes,
     * nothing guarantees another recompute before the user's chance to report
     * has passed — so the caller leaves the current answer alone instead.
     */
    private fun isCrashed(file: File): Boolean? =
        if (file == current) {
            markerSaysCrashed()
        } else {
            when (classify(file)) {
                PriorEntry.RUN -> file.name.endsWith(PREVIOUS_CRASH_SUFFIX)
                PriorEntry.REMOVABLE -> false
                PriorEntry.UNKNOWN -> null
            }
        }

    /** When [file] was last written, or null when that could not be read. */
    private fun modifiedAt(file: File): Long? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            .lastModifiedTime()
            .toMillis()
    }.getOrNull()

    /** What a `debug-prev-*` entry is, as far as the retention bound cares. */
    private enum class PriorEntry {
        /** A regular file with something in it. Only these spend a slot. */
        RUN,

        /**
         * Known not to be a run — an empty file, one already gone, or something
         * that is not a file at all. Never counted, always offered for removal.
         */
        REMOVABLE,

        /** Nobody could tell. Never counted, and never removed. */
        UNKNOWN,
    }

    /**
     * Sorts a rotated entry into the three kinds above.
     *
     * Read through `java.nio.file` for the reason the rest of the rotation is:
     * `isFile` and `length()` answer false and zero both for a real absence and
     * for a lookup that failed, and here that answer decides whether an entry
     * spends a retention slot (Codex, PR #4). An entry nobody could classify is
     * [PriorEntry.UNKNOWN] — counting it would prune a genuine run to keep
     * something that cannot be read into a report either, and removing it is the
     * one irreversible answer to "could not tell".
     */
    private fun classify(file: File): PriorEntry = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        when {
            !attributes.isRegularFile -> PriorEntry.REMOVABLE
            attributes.size() > 0L -> PriorEntry.RUN
            else -> PriorEntry.REMOVABLE
        }
    }.getOrElse { if (it is NoSuchFileException) PriorEntry.REMOVABLE else PriorEntry.UNKNOWN }

    /**
     * Prior runs' files, oldest first by last-modified time — or null when the
     * directory could not be listed at all.
     *
     * `File.listFiles` answers null for an unreadable directory or an I/O
     * failure, and that is not the same fact as an empty directory. A caller
     * that must not mistake "couldn't look" for "nothing there" needs the two
     * apart (see [recomputeUnacknowledgedCrash]).
     */
    private fun previousFilesOrNull(): List<File>? = previousRunsOrNull()?.map { it.file }

    /** A rotated entry paired with the one modification-time read taken for it. */
    private class PriorRun(val file: File, val modifiedAt: Long?)

    /** See [previousFilesOrNull]; this is the form that carries the read time. */
    private fun previousRunsOrNull(): List<PriorRun>? {
        // Ordered by a time read through `java.nio.file`, not `lastModified()`,
        // which answers 0 both for the epoch and for a read that failed — and
        // that zero sorts the entry *oldest*, straight into the pruned set, so a
        // transient metadata failure could delete the newest diagnostic there is
        // (Codex, PR #4). A time nobody could read sorts last instead, and the
        // prune skips it outright.
        val listed = dir.listFiles { file -> file.name.startsWith(PREVIOUS_PREFIX) } ?: return null
        // Read once and carried, not read per comparison and again per decision.
        // `sortedWith` calls its selector on every comparison and the prune then
        // asked again, so a failure during the sort could put the actually-oldest
        // run last while a later successful read said the order was known —
        // deleting a newer run on an order derived from a different answer
        // (Codex, PR #4). One read per entry, used for both.
        val rotated = listed.map { PriorRun(it, modifiedAt(it)) }
            .sortedWith(compareBy(nullsLast()) { it.modifiedAt })
        // Last, because a run left in place by a failed rotation is the one that
        // ended most recently.
        return rotated + listOfNotNull(retainedPreviousRun()?.let { PriorRun(it, modifiedAt(it)) })
    }

    /**
     * Prior runs' files, best-effort: an unlistable directory reads as no prior
     * runs. That is the right answer for the rotation's pruning and for the
     * share's read, where there is nothing to do either way and nothing is
     * destroyed on the strength of it — [previousFilesOrNull] is for the crash
     * check, which cannot afford the conflation.
     */
    private fun previousFiles(): List<File> = previousFilesOrNull() ?: emptyList()

    /**
     * What a pass over the prior runs found: the ones that crashed, and whether
     * any entry refused to say.
     */
    private class CrashScan(val crashed: List<File>, val unknown: Boolean)

    /**
     * Worker-only. Null when the directory could not be listed at all — the one
     * fact that is not the same as "no crashes" (see [previousFilesOrNull]).
     */
    private fun scanForCrashes(): CrashScan? {
        val files = previousFilesOrNull() ?: return null
        val crashed = mutableListOf<File>()
        var unknown = false
        for (file in files) {
            when (isCrashed(file)) {
                true -> crashed += file
                false -> Unit
                null -> unknown = true
            }
        }
        return CrashScan(crashed, unknown)
    }

    // ---------------------------------------------------------------- startup

    /**
     * Enqueues the just-ended run's rotation on the worker and, unless told not
     * to, installs a chained uncaught-exception handler. Call once, early in
     * `Application.onCreate`, and register this as a sink afterward.
     *
     * The rotation is enqueued rather than run inline, so `onCreate` does no
     * synchronous disk I/O; the FIFO worker still runs it before any of this
     * run's writes.
     *
     * [installCrashHandler] is there for an app that owns its own handler chain
     * and would rather call [flushForCrash] from it — and for tests, which must
     * not leave a handler installed on the JVM they share.
     */
    @JvmOverloads
    fun start(installCrashHandler: Boolean = true) {
        // Rotate on the worker, never on the calling (main) thread: onCreate is on
        // the cold-start path and must not block on disk. FIFO ordering means this
        // runs before this run's writes and the crash flush, so the prior run is
        // renamed aside before anything can clobber it.
        runCatching {
            worker.execute {
                // Set only once `debug.log` is known safe to write over: either
                // it was moved aside (or was not there) *and* the marker that
                // would classify it is gone. Anything thrown before that leaves
                // the previous run sitting in `debug.log`, where the first
                // mirror write destroys it — the same loss the `moveAside`
                // answer is checked for, by an exceptional route (Codex, PR #4).
                var safeToMirror = false
                runCatching {
                    var moved = true
                    // Not guessed, and not read twice. Every step of the rotation
                    // is one-way, so acting on a state nobody could read loses the
                    // previous run or its banner permanently — where deferring
                    // costs one launch, with the run and its marker both still in
                    // place for the next start to try again (Codex, PR #4).
                    var deferReason: String? = null
                    val present = currentRunPresent()
                    // Was the just-ended run a crash? The uncaught-exception
                    // handler leaves the marker behind; a graceful exit or a
                    // routine kill does not. Only a crashed run gets the crash
                    // suffix that raises the banner.
                    val crashed = if (present == true) markerSaysCrashed() else false
                    if (present == null) {
                        deferReason =
                            "Whether there is a previous run could not be read, so this run is not being saved"
                        moved = false
                    } else if (crashed == null) {
                        deferReason =
                            "The previous run's crash record could not be read, so this run is not being saved"
                        moved = false
                    } else if (present) {
                        val suffix =
                            if (crashed == true) PREVIOUS_CRASH_SUFFIX else PREVIOUS_PLAIN_SUFFIX
                        val rotated = File(dir, "$PREVIOUS_PREFIX${rotationName()}$suffix")
                        moved = moveAside(current, rotated)
                        // Bound how many unshared runs pile up; drop the oldest.
                        //
                        // The bound counts *runs*, and only a non-empty regular
                        // file is one. An opt-out landing between the purge and a
                        // debounced write already scheduled leaves `debug.log`
                        // holding nothing, and rotating that in would prune a real
                        // diagnostic to keep a placeholder carrying no lines at
                        // all. A directory sitting under a prior-run name -- the
                        // obstruction a failed rotation leaves behind -- did the
                        // same thing for the same reason: it is not a run and
                        // cannot be read into a report, so counting it spent a
                        // permanent slot and deleted a genuine run at every start
                        // to make room for something that was never a diagnostic
                        // (Codex, PR #4).
                        // Anything known not to be a run is offered for removal
                        // rather than counted, and answered for by the warning
                        // below when it refuses -- which is what the directory
                        // does. An entry nobody could classify is neither: not
                        // counted, and not removed, since deleting what could not
                        // be identified is the one irreversible answer to "could
                        // not tell", and the report already says it could not be
                        // read.
                        // Pruning at all needs *every* counted run ordered, not
                        // just the ones being dropped. Skipping the unreadable one
                        // and taking the next-oldest still chooses from a partial
                        // order: the entry with no time may be older than the one
                        // deleted for it, so the newest diagnostic goes and the
                        // oldest stays (Codex, PR #4). Nothing is lost by waiting
                        // — the bound is exceeded by one launch, and the next start
                        // prunes once the times read.
                        // And a rotation that *failed* prunes nothing at all.
                        // The copy fallback can leave a partial file already
                        // wearing a prior-run name, and a partial is a non-empty
                        // regular file -- a `RUN`, and the newest one, so the
                        // bound would delete the oldest genuine diagnostic to
                        // make room for the wreckage of the move that just
                        // failed (Codex, PR #4). Nothing was added to the set
                        // this launch, so there is nothing the bound needs doing
                        // about; the next start prunes once a rotation lands.
                        val entries = if (moved) previousRunsOrNull().orEmpty() else emptyList()
                        val byKind = entries.groupBy { classify(it.file) }
                        // The run this launch just rotated is the newest by
                        // construction -- we created it a moment ago -- so it is
                        // not a candidate whatever its timestamp says. A wall
                        // clock that moved backward between runs leaves it with an
                        // mtime older than every run before it, and the prune,
                        // ordering by that, would delete the run that just ended
                        // (Codex, PR #4). Under the crash suffix that is the whole
                        // failure: the marker is consumed a few lines below, so
                        // the banner recompute then finds no crash and the user
                        // learns nothing about the one that just happened. It is
                        // still *counted*, so the bound still holds -- what it
                        // stops being is deletable.
                        val runs = byKind[PriorEntry.RUN].orEmpty()
                        val candidates = runs.filter { it.file != rotated }
                        val ordered = runs.all { it.modifiedAt != null }
                        val excess =
                            if (ordered) (runs.size - MAX_PREVIOUS_RUNS).coerceAtLeast(0) else 0
                        if (!ordered && runs.size > MAX_PREVIOUS_RUNS) {
                            log.warning("Old runs could not be ordered, so none was pruned")
                        }
                        val kept = (byKind[PriorEntry.REMOVABLE].orEmpty() + candidates.take(excess))
                            .count { !discardContents(it.file) }
                        // A prune that silently did not happen leaves the run in
                        // every later report and defeats the bound it is here to
                        // enforce (Codex, PR #4).
                        if (kept > 0) log.warning("%s old run(s) could not be pruned", kept)
                    }
                    if (moved) {
                        // Consume the just-read marker so it can't mislabel this
                        // run — and if it cannot be consumed, do not write beside
                        // it. The recovery path already checked this answer and
                        // this one discarded it, which is the same false banner by
                        // a different route (Codex, PR #4).
                        if (consumeCrashMarker()) {
                            safeToMirror = true
                        } else {
                            standDownFromMirroring(
                                "An earlier crash marker could not be cleared, so this run is not being saved",
                            )
                        }
                    } else if (deferReason != null) {
                        // Same shape as a refused rename: the run and its record
                        // stay where they are, so the next start can try again.
                        standDownFromMirroring(deferReason)
                    } else {
                        // Left in place on purpose: it is what classifies the run
                        // still sitting in `debug.log`, both for this launch and
                        // for the rotation the next start retries.
                        standDownFromMirroring(
                            "Previous run could not be rotated aside, so this run is not being saved",
                        )
                    }
                    // Discard any leftover temp from a write interrupted mid-flight:
                    // never renamed into place, so by the atomic-write contract it is
                    // uncommitted and possibly partial. Deliberately unreported: the
                    // next write replaces it, and it is never read by anything here,
                    // so a failure to remove it costs a stale file and nothing else.
                    discardContents(temp)
                }.onFailure {
                    log.failure(it, "The previous run could not be rotated")
                    if (!safeToMirror) {
                        standDownFromMirroring(
                            "The previous run could not be rotated, so this run is not being saved",
                        )
                    }
                }
                // Derive the banner's state from the record the rotation just
                // settled -- the first of the three points where it changes.
                recomputeUnacknowledgedCrash()
            }
        }.onFailure {
            // Latched *before* the failure is reported, and that order is
            // load-bearing: reporting records a line, recording fans out to this
            // sink, and this sink schedules a write with no delay -- so a write
            // could reach the worker and overwrite the previous run while this
            // thread was still on its way to setting the flag.
            //
            // The one cross-thread stand-down, and the reason the latch is
            // volatile: the task that would have set it on the worker was
            // refused, so nothing else will, while a *later* submission the
            // worker does accept would write over the previous run still sitting
            // in `debug.log` (Codex, PR #4). Safe to latch from here because it
            // is one-way and toward the safe answer, and because the rotation
            // that would have cleared it never ran.
            standDownFromMirroring(
                "The startup rotation could not be scheduled, so this run is not being saved",
            )
            log.failure(it, "The startup rotation could not be scheduled")
        }
        if (installCrashHandler) installCrashHandler()
    }

    /**
     * Moves the previous run's file aside, answering whether it is now safe to
     * write over [current].
     *
     * `renameTo` is checked rather than assumed: it answers false where the
     * filesystem refuses the operation, and the previous code carried on
     * regardless — consuming the crash marker and then letting the first mirror
     * write replace `debug.log`, so a run that had crashed was destroyed along
     * with the record that it had (Codex, PR #4). Both copies this port was
     * merged from discard that result, so this is a bug that shipped twice.
     *
     * A copy is tried when the rename is refused, since a rename can fail where
     * an ordinary read-and-write still works. It refuses to overwrite: the
     * destination name is unique per rotation, so an existing one means
     * something unaccounted for is already there, and clobbering another run's
     * log to file this one is the wrong trade. A copy that lands **discards the
     * source** rather than leaving it for the mirror to replace: that reasoning
     * held only while a write was certain to follow, and a process ending first
     * left the same run in both places, so the next start rotated it a second
     * time — duplicated in every report, and spending a retention slot that can
     * prune a genuine run (Codex, PR #4). Truncating counts, since nothing empty
     * is ever offered as a run.
     *
     * A copy that *throws part way* is the case worth naming: it leaves a
     * half-written file already wearing a prior-run name, which the rest of
     * startup then treats as a run — read into the next report, counted against
     * the retention bound, and, under the crash suffix, raising a banner for
     * content that is not a run (Codex, PR #4). So a copy that created the
     * destination and then failed takes it back out again. Only that one: a
     * destination that was already there is the refusal above, and is not this
     * rotation's to delete.
     */
    private fun moveAside(from: File, to: File): Boolean {
        // Never rename onto a name that is taken. `renameTo` does not refuse:
        // POSIX rename *replaces* an existing regular destination and answers
        // true, so the refusal this doc describes existed only in the copy
        // fallback below and the rename path clobbered silently. Nor are the
        // names as unique as "unique per rotation" claimed -- `System.nanoTime()`
        // measures uptime, whose origin resets at boot, so two runs started a
        // similar interval after two different boots can name the same file
        // (Codex, PR #4). What it destroys is an unshared prior run, permanently.
        //
        // Unknown counts as taken, for the reason every other unknown in this
        // file does: the cost of a wrong "free" is the run it overwrites. A
        // collision stands the rotation down and keeps the previous run, and it
        // heals itself at the next start, which generates a different name.
        if (presenceOf(to) != false) return false
        if (runCatching { from.renameTo(to) }.getOrDefault(false)) return true
        // Assume it was already there when the question cannot be answered: the
        // cost of a wrong "no" is deleting a file that is not ours.
        //
        // Read through attributes, not `exists()`, which is the eighth place in
        // this file where that API's single `false` for "not there" and "could
        // not look" was load-bearing (Codex, PR #4). `runCatching` catches
        // nothing here, because `exists()` does not throw -- it just answers
        // false -- so a stat refused on a destination that genuinely holds a
        // prior run made `copyTo` throw and then handed that run to
        // `discardContents` as something this rotation had created. Only a
        // definite absence makes the destination ours to clean up.
        //
        // Uncovered, like the rest of this fallback's interior and for the same
        // reason: on one filesystem the only destination that makes `renameTo`
        // fail is a non-empty directory, whose attributes read perfectly well,
        // and a symlink loop -- the fixture that does defeat `exists()`
        // elsewhere -- is *replaced* by the rename rather than obstructing it.
        // The difference shows only where a stat is refused over a name that is
        // really taken, which no fixture here can construct.
        val existed = presenceOf(to) ?: true
        return runCatching {
            from.copyTo(to)
            // See above: the destination is authoritative now, so the source must
            // stop being a second copy of the same run. A discard that *fails* is
            // not a move that succeeded, and answering true here left exactly the
            // duplicate the paragraph above rules out -- so the copy comes back
            // out and the answer is false, standing the mirroring down and
            // leaving the previous run whole for the next start to retry
            // (Codex, PR #4). Nothing is lost by removing it: it is only removed
            // because the source it was copied from is still there.
            if (discardContents(from)) {
                true
            } else {
                log.warning("A previous run was copied aside but the original could not be removed")
                // Take the copy back out only when the source is *verified* to
                // still hold the run. A refused discard is not proof of that: the
                // truncation fallback empties the file as it opens it, so one that
                // throws on the write or the close answers false over a source
                // that is already gone — and deleting the copy then loses the run
                // from both places (Codex, PR #4). Keeping both is the lesser
                // cost, and it is the duplicate the `false` below already stands
                // mirroring down over.
                if (!existed && classify(from) == PriorEntry.RUN) discardContents(to)
                false
            }
        }.getOrElse {
            // A discard that itself fails leaves the partial, which is the same
            // residue the prune's warning covers at the next start; nothing more
            // can be done here, and the stand-down that follows this `false` is
            // already reported.
            if (!existed) discardContents(to)
            false
        }
    }

    /**
     * Set when this run must not be written to `debug.log`.
     *
     * Three causes, all meaning the file would say something untrue. A rotation
     * that could not move the previous run aside leaves that run there, so a
     * write would destroy it. A crash marker that could not be consumed stays
     * beside whatever is written next, so a write would hand the crash suffix to
     * a run that did not crash. A rotation the worker refused to accept never
     * ran at all, so the previous run is still there and nothing on the worker
     * is going to say so (Codex, PR #4). The name is the effect rather than any
     * of the causes, since the callers all care about the effect.
     *
     * Read and cleared on the worker; `@Volatile` for that third cause alone,
     * which latches it from the calling thread because the worker cannot.
     */
    @Volatile
    private var mirroringStoodDown = false

    /**
     * Gives up persisting *this* run, because writing would destroy the previous
     * one where it still lies.
     *
     * The previous run is the one already known to have ended badly — it may
     * hold the crash the user is about to report, and its marker is left in
     * place so a later start can still classify it. This run is not lost in the
     * same way: it is in memory, and in whatever other sinks are registered. So
     * where only one of the two can be kept, it is the older one, and the next
     * start retries the rotation that failed here.
     */
    private fun standDownFromMirroring(reason: String) {
        if (mirroringStoodDown) return
        mirroringStoodDown = true
        // Held if it did not land. Recording can be off while `start()` runs, and
        // the flag latching over a discarded line leaves every snapshot from this
        // process suppressed with nothing anywhere to explain it (Codex, PR #4).
        // The same shape as the opt-out purge's report, and said in the same
        // place: the next write that can record one.
        if (!log.warning(reason)) standDownReason = reason
    }

    /**
     * A stand-down reason no log was open to receive. Worker-only but for the
     * refused-enqueue latch above, and `@Volatile` for the same reason.
     */
    @Volatile
    private var standDownReason: String? = null

    /** Worker-only. See [standDownFromMirroring]. */
    private fun reportStandDown() {
        val reason = standDownReason ?: return
        if (log.warning(reason)) standDownReason = null
    }

    /**
     * Worker-only: mirroring is safe again. The held reason goes with it — it
     * explains a suppression that is over, and saying it afterwards would read
     * as a fresh one.
     */
    private fun resumeMirroring() {
        mirroringStoodDown = false
        standDownReason = null
    }

    // ----------------------------------------------------------------- mirror

    override fun log(line: String) {
        // Debounced coalesce: the first line since the last write schedules one
        // write [writeDebounceMs] out; every line until then is already captured by
        // that write's snapshot read, so none queues another. The token is
        // cleared inside the scheduled task rather than before the delay, so the
        // whole window coalesces into a single full-file write instead of one per
        // entry. Enqueue-only, so this returns to the caller at once — which is
        // what `Sink` requires, since this runs inside the fan-out.
        val token = Any()
        if (writeToken.compareAndSet(null, token)) {
            runCatching {
                worker.schedule(
                    // Named rather than unconditional: `awaitIdle` may have run
                    // this window's write already, and the token is what says so.
                    { runDebouncedWrite(token) },
                    writeDebounceMs,
                    TimeUnit.MILLISECONDS,
                )
            }.onFailure { failure ->
                // Conditional, and that is the whole point of the token. This
                // thread can be descheduled between the refusal and here, long
                // enough for `awaitIdle` to clear the slot and a later line to
                // claim it with a write that *was* accepted; an unconditional
                // clear would cancel that newer write and leave the file stale
                // until another line happened along (Codex, PR #18).
                writeToken.compareAndSet(token, null)
                // Held rather than said here, and this is the one enqueue that
                // could not simply report: `log` runs inside the core's fan-out,
                // so a line recorded from it is dropped by the delivering-thread
                // guard. Silence left the persisted copy stale or absent with
                // nothing anywhere to say persistence had stopped (Codex, PR #4).
                // Said by the first write that does get scheduled -- which is the
                // moment it is worth saying, since it explains the gap that write
                // is about to leave in the file. A worker that never accepts
                // another cannot say it, and cannot write the file either.
                writeScheduleFailures.incrementAndGet()
                writeScheduleFailure.compareAndSet(null, failure)
            }
        }
    }

    /**
     * Worker-only: the body [log] debounces, and what [awaitIdle] runs early
     * when it brings that window to an end. Clearing the token here rather than
     * before the delay is what coalesces the whole window into one full-file
     * write instead of one per entry.
     *
     * The compare-and-set is what makes the window run exactly once no matter
     * which of the two gets there first: whichever claims [token] does the
     * write, and the other returns having done nothing.
     */
    private fun runDebouncedWrite(token: Any) {
        if (!writeToken.compareAndSet(token, null)) return
        reportPurgeFailure()
        reportStandDown()
        reportWriteScheduleFailures()
        writeSnapshot()
    }

    /** Refused mirror-write schedulings, held for the first write that lands. */
    private val writeScheduleFailures = AtomicInteger()
    private val writeScheduleFailure = AtomicReference<Throwable?>()

    /**
     * Worker-only. See [log]. Written from every recording thread, so the count
     * is decremented by what was reported rather than zeroed — a refusal landing
     * while this runs is still owed a line.
     */
    private fun reportWriteScheduleFailures() {
        val failures = writeScheduleFailures.get()
        if (failures == 0) return
        val cause = writeScheduleFailure.get()
        val writes = if (failures == 1) "write" else "writes"
        val message = "%s saved-copy %s could not be scheduled, so this run's log has a gap"
        val said =
            if (cause != null) log.failure(cause, message, failures, writes)
            else log.warning(message, failures, writes)
        if (said) {
            writeScheduleFailures.addAndGet(-failures)
            writeScheduleFailure.set(null)
        }
    }

    /**
     * Recording was turned off, so the durable copy goes with the buffer.
     *
     * Without this, "off" held only in memory: the core discards what it
     * collected, this file keeps it, and the next start rotates it into the
     * prior-run set where a report can still pick it up — entries from before
     * the user opted out, kept by the one component that outlives the buffer
     * (Codex, PR #4).
     *
     * **Only this run's file.** The prior-run files are not touched: they were
     * kept across a rotation, one of them may be the crash the user is part way
     * through sending, and turning recording off now is not an instruction to
     * destroy a report already offered. This mirrors what the core itself does —
     * it clears the buffer, not the log's whole history on disk.
     *
     * Enqueue-only, like [log], since this runs under the buffer's monitor — and
     * that leaves a window: a process killed between `setRecording(false)`
     * returning and this task draining leaves the file, and the next start
     * rotates it into the prior-run set where a report can still pick it up
     * (Codex, PR #4). The window is one in-flight file operation wide, since the
     * task is enqueued with no delay and so runs ahead of any debounced write.
     * Closing it properly needs a durable record of the request, which has the
     * same problem, or a synchronous write here, which would block every
     * recorder under this monitor. It is written up in `TODO.md`.
     */
    override fun onCleared() {
        // Deliberately uncontained. A refused enqueue means the purge never
        // runs, so `debug.log` survives an opt-out with its pre-opt-out
        // contents — and nothing here could hold that failure instead: the
        // flags that would carry it are worker-only, and the worker is what
        // is refusing. `DebugLog` isolates every `onCleared` call and holds
        // what it threw until recording is back on, so letting this out is
        // what puts the failure somewhere a reader finds it (Codex, PR #4).
        runCatching {
            worker.execute(::purgeOnWorker)
        }.onFailure { failure ->
            // The task never ran, so nothing on the worker will ever publish
            // this operation's outcome — and `DebugLog` holds the rejection
            // for a log the user has just turned off (Codex, PR #20).
            recordStorageFailureOffWorker(optOutPurgeFailed = true)
            // Still uncontained, for the reason above: letting it out is what
            // puts the failure somewhere a reader finds it.
            throw failure
        }
    }

    /** Worker-only. The body of [onCleared]; see its documentation. */
    private fun purgeOnWorker() {
        val kept = runCatching {
            // Not while a failed rotation left the previous run here.
            // That file is a prior run, and the rule above is that prior
            // runs survive an opt-out (Codex, PR #4).
            val currentLeft = retainedPreviousRun() == null && !discardContents(current)
            // The temp file counts the same. A residual snapshot there is
            // this run's own entries, so an opt-out that could not remove
            // it left exactly what it promised to delete -- and ignoring
            // its answer reported success for it (Codex, PR #20).
            val tempLeft = !discardContents(temp)
            val left = currentLeft || tempLeft
            if (left) purgeFailed = true
            left
        }.onFailure { failure ->
            // The same held state as a refusal, because it is the same
            // outcome: the file survives an opt-out and the next start
            // rotates it into the shareable set. Swallowed, it left no
            // record and no diagnostic at all (Codex, PR #4). Nothing
            // can be said here — recording is already off, which is what
            // the holding is for.
            purgeFailed = true
            purgeFailure = failure
            // Whatever threw, the file this was meant to remove is still
            // there as far as anyone can tell -- the honest answer, and
            // the same one the held report will eventually give.
        }.getOrDefault(true)
        // Said to the caller as well as to the log, because the log is the
        // one place this line may never reach: recording is off by the time
        // this runs, so the report is held until it comes back and may be
        // held for the life of the process.
        publishStorageOutcomes(optOutPurgeFailed = kept)
    }

    /**
     * Worker-only: the opt-out could not get rid of this run's file, and there
     * was no working log to say so in.
     *
     * A purge that fails is exactly the leak the purge exists to close, so it
     * cannot go unsaid — but `setRecording(false)` has already taken effect by
     * the time [onCleared] runs, so anything recorded there is dropped by the
     * gate. Reporting into a log the user has just turned off is not reporting.
     *
     * So it is held and said on the next write, which happens only once
     * recording is back on: if it never is, there is no log for the line to
     * appear in and nothing was lost by holding it.
     */
    private var purgeFailed = false

    /**
     * Worker-only: what threw, when the purge failed exceptionally rather than
     * being refused. Held so the eventual report can name the failed call, which
     * "it did not work" cannot. Rendered by type and frames only, like every
     * throwable this log carries.
     */
    private var purgeFailure: Throwable? = null

    /**
     * Begins a reporting spell, answering whether one is now in effect.
     *
     * Every once-per-spell guard here has to survive a disabled log. Recording
     * can be off while these paths run — a debounced write scheduled before
     * `setRecording(false)` still fires after it — and a line recorded then is
     * dropped by the gate. Latching the guard on that line would spend the
     * spell on a report nobody received, and the failure would then repeat in
     * silence for the life of the process (Codex, PR #4).
     *
     * So the guard latches only when the line actually lands, and [report] is
     * whatever `DebugLog` call says whether it did. Asking `isRecording` first
     * and then reporting was the same bug one window narrower: the gate can
     * close between the question and the entry, and the answer says nothing
     * about the log's other two drops — a call from inside a sink's delivery,
     * and a line that failed to render (Codex, PR #4).
     */
    private fun beginSpell(failing: Boolean, report: () -> Boolean): Boolean =
        if (failing) true else report()

    /**
     * Worker-only. See [purgeFailed] — held until recording is back on, since
     * that is the only state in which the line can be recorded at all.
     */
    private fun reportPurgeFailure() {
        if (!purgeFailed) return
        // Held until the line actually lands, for [beginSpell]'s reason: this
        // one is reached from the debounced write, which can fire after the
        // opt-out that set the flag, and clearing it alongside a dropped line
        // would lose the report for good.
        val message = "An earlier opt-out could not remove this run's saved log"
        val cause = purgeFailure
        val said = if (cause != null) log.failure(cause, message) else log.warning(message)
        if (said) {
            purgeFailed = false
            purgeFailure = null
        }
    }

    /** Worker-only: whether the last mirror write failed, so a repeat stays quiet. */
    private var writeFailing = false

    private fun writeSnapshot() {
        // Writing here would replace a previous run the rotation could not move
        // out of the way. Reported once, at the point it was decided — and
        // re-checked here, because one of the two causes has no other way back.
        if (mirroringStoodDown && !standDownStillNeeded()) resumeMirroring()
        if (mirroringStoodDown) return
        runCatching {
            // Trimmed, composed and anchored in one call, because those three
            // steps do not commute — see [DebugLog.boundedSnapshot].
            val text = log.boundedSnapshot(
                pinnedBudgetChars = PINNED_PERSIST_BUDGET_CHARS,
                recentBudgetChars = PERSIST_BUDGET_CHARS - PINNED_PERSIST_BUDGET_CHARS,
            ).joinToString("\n")
            // Atomic replace: write a temp file, then rename it over the current
            // one. A kill mid-write then leaves the prior *complete* snapshot
            // intact rather than a truncated or empty file — surviving exactly
            // that kill is the point. Fall back to a direct write only if the
            // rename is refused.
            temp.writeText(text)
            if (!temp.renameTo(current)) {
                // The atomic path is gone, so this write is the one that can be
                // caught half-done. It throws on failure rather than answering,
                // and the enclosing runCatching is where that lands.
                current.writeText(text)
                discardContents(temp)
            }
        }
            .onFailure { failure ->
                // A mirror that quietly stops writing is the whole feature
                // failing with nothing to say so: the log looks like it is being
                // kept, and the next launch finds nothing. Once per spell, since
                // this runs on every entry and reporting fans out to this sink,
                // which would queue another write and another report.
                writeFailing = beginSpell(writeFailing) {
                    log.failure(failure, "The debug log could not be saved")
                }
            }
            .onSuccess { writeFailing = false }
    }

    // ------------------------------------------------------------ prior runs

    /**
     * Handles handed out by [readPreviousRun] and not yet cleared.
     *
     * Worker-owned, and here for exactly one reason: the crash-banner dismissal
     * renames a file that an in-flight report may already be holding, and that
     * rename has to be followed into every handle that names it or the eventual
     * clear misses and the log rides the next report too.
     *
     * It replaced a single `lastSurfaced` list, plus three ticket fields that
     * existed only to police it. Those answered "is the one shared slot still
     * meaningful?", and three review rounds each found a case they could not:
     * an enqueue refused before any task existed, a non-monotonic write from an
     * out-of-order handler, and two reads that both *succeeded* — where the
     * earlier caller's clear deleted a file only the later read had surfaced
     * (Codex, PR #4). A handle carries its own set, so the question is gone
     * rather than answered again: a caller can only ever clear what it was
     * given, and a caller that was given nothing has nothing to clear.
     *
     * Held **weakly**, because a handle is only worth remapping while somebody
     * still has it. A report the user opens and then cancels never calls
     * [clearPreviousRun] — that is the documented flow, not a mistake — so
     * strong references would accumulate one handle and its file list per
     * canceled attempt, for the life of a process that is normally the app's
     * (Codex, PR #6). A dropped handle is unreachable, so it can never be
     * cleared and never needs remapping; collecting it is exactly right.
     * Entries whose referent is gone are pruned as they are passed over.
     */
    private val outstanding = mutableListOf<WeakReference<PreviousRun>>()

    /** Live handles, dropping the entries whose callers have let go. */
    private fun liveHandles(): List<PreviousRun> {
        val live = mutableListOf<PreviousRun>()
        outstanding.removeAll { reference ->
            val handle = reference.get()
            if (handle != null) live += handle
            handle == null
        }
        return live
    }

    /**
     * Every unshared prior run's log, oldest first and newest-bounded, or null
     * when the last run(s) left nothing to send — or when the read itself could
     * not be completed, which is reported to the log rather than to the caller.
     *
     * Runs on the worker, so it is ordered *after* the startup rotation [start]
     * queued there — otherwise a share racing a slow rotation could scan before
     * `debug.log` is renamed and miss the just-ended run. Call it off the main
     * thread: it blocks on the worker and reads up to [MAX_PREVIOUS_RUNS] files.
     * A file that fails to read is skipped and left in place, never destroyed —
     * it is not added to the handle's set, so clearing this report leaves it for
     * the next one.
     *
     * The handle it answers with is what [clearPreviousRun] consumes, so a
     * report deletes exactly the files it was built from — overlapping flows
     * included, and a caller that got nothing deletes nothing.
     */
    fun readPreviousRun(): PreviousRun? =
        runCatching { worker.submit<PreviousRun?> { readPreviousRunOnWorker() }.get() }
            .onFailure { failure ->
                // Nothing to undo. The task may still be queued and may still
                // build a handle, but this caller never received it and no other
                // caller can name it, so nothing it lists can be deleted by
                // anybody. That is the whole reason the ticket fields are gone:
                // they existed to decide whether a *shared* slot still meant
                // anything, and there is no shared slot now (Codex, PR #4).
                log.failure(failure, "Earlier runs could not be read")
                // `get()` clears the flag when it throws this, and swallowing it
                // would strand a caller that is being asked to stop.
                if (failure is InterruptedException) Thread.currentThread().interrupt()
            }
            .getOrNull()

    private fun readPreviousRunOnWorker(): PreviousRun? {
        // The listing itself, not the best-effort view: a directory that could
        // not be read is a different fact from one with nothing in it, and
        // collapsing them here made an unavailable prior log read as no prior log
        // — the same swallow as the per-file one, one level up (Codex, PR #4).
        // The handle lists nothing, so clearing this report deletes nothing --
        // the files are still in the directory for the next successful read to
        // find, which is the retry this preserves.
        val files = previousFilesOrNull() ?: run {
            log.warning("Earlier runs could not be listed")
            // Incomplete in the strongest sense: not one run was accounted for,
            // and every one of them is still on disk.
            return handleFor("[earlier runs could not be listed]", mutableListOf(), complete = false)
        }
        // Kept per file rather than flattened, so the bound below can be
        // attributed back to the file whose lines it dropped.
        val perFile = mutableListOf<Pair<File, List<String>>>()
        var unreadable = 0
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null) {
                unreadable++
                continue
            }
            perFile += file to text.split("\n").filter { it.isNotEmpty() }
        }
        val readable = perFile.map { it.first }.toMutableList()
        val lines = perFile.flatMap { it.second }
        if (unreadable > 0) log.warning("%s earlier run(s) could not be read", unreadable)
        if (lines.isEmpty()) return handleFor(unreadableNotice(unreadable), readable, unreadable == 0)
        // After the bound, not before: a notice prepended first would be the
        // oldest line and so the first one trimmed.
        val kept = boundedLogTail(lines, PERSIST_BUDGET_CHARS)
        // The trim is the second way this handle can cover less than it
        // consumes, and the only one that would *destroy* what it left out: a
        // skipped run stays on disk because it never entered the handle, but a
        // run the bound dropped whole was read, listed, and would be deleted by
        // the share that carried none of it (Codex, PR #20). So it is dropped
        // from the handle too — a handle consumes exactly the files its text
        // speaks for, and the next share, with the newer runs gone, carries it.
        val consumable = filesInside(perFile, droppedLines = lines.size - kept.size)
        // Still not the whole account, though, and the caller needs to know:
        // the crash it is about to retire a banner for may be one of the runs
        // left behind. Compared by content, not by count, since a lone
        // surviving line can be truncated in place instead of dropped.
        val complete = unreadable == 0 && kept == lines
        val tail = kept.joinToString("\n")
        if (tail.isBlank()) return handleFor(unreadableNotice(unreadable), consumable, complete)
        return handleFor(
            listOfNotNull(unreadableNotice(unreadable), tail).joinToString("\n"),
            consumable,
            complete,
        )
    }

    /**
     * The files whose lines survived a trim of [droppedLines] from the front.
     *
     * The bound drops oldest-first, and [perFile] is in that same order, so
     * walking it and spending the count says which files lost *everything*. A
     * file whose last lines were cut but whose first survived is kept: a tail
     * is what this log offers, and excluding a partially-trimmed run would mean
     * a single over-budget run could never be consumed at all.
     */
    private fun filesInside(
        perFile: List<Pair<File, List<String>>>,
        droppedLines: Int,
    ): MutableList<File> {
        var remaining = droppedLines
        val kept = mutableListOf<File>()
        for ((file, lines) in perFile) {
            // An empty file contributed nothing to drop and nothing to keep. It
            // is still this run's file and still consumable — but it must not
            // spend or clear the outstanding count, which belongs entirely to
            // the files that had lines. Falling through to the reset below let
            // an empty run sorted ahead of a wholly-dropped one clear the debt
            // and hand that run back to be deleted unsent (Codex, PR #20).
            if (lines.isEmpty()) {
                kept += file
                continue
            }
            if (remaining >= lines.size) {
                remaining -= lines.size
                continue
            }
            // The first file with a surviving line. Everything after it survives
            // too, so the count is spent.
            remaining = 0
            kept += file
        }
        return kept
    }

    /**
     * A handle for [text], registered so a dismissal's rename can be followed
     * into it — or null when there is nothing to report, which is also nothing
     * to clear. Null therefore keeps its old meaning here: *the last run(s) left
     * nothing*, not *the read failed*, which is what [readPreviousRun] answers
     * null for when its own wait does not complete.
     */
    private fun handleFor(text: String?, files: MutableList<File>, complete: Boolean): PreviousRun? =
        text?.let {
            PreviousRun(it, files, complete).also { handle ->
                liveHandles()
                outstanding += WeakReference(handle)
            }
        }

    /**
     * A line for the report itself when a prior run could not be read, or null.
     *
     * The log line is for whoever is diagnosing the reader; this is for whoever
     * reads the *report*, and it is the one that matters. Swallowing the failure
     * produced a report that looked complete while the run it was sent about was
     * missing from it, so the person reading it had no way to know (Codex,
     * PR #4). It carries a count and no path.
     */
    private fun unreadableNotice(unreadable: Int): String? =
        if (unreadable > 0) "[$unreadable earlier run(s) could not be read]" else null

    /**
     * Deletes exactly the files [run] was built from — the handle [readPreviousRun]
     * answered with, so a report consumes what it actually contained and nothing
     * else. On the worker too, so it cannot race the mirror's writes, and so the
     * handles are read and mutated under one single-threaded ordering.
     *
     * **Enqueue-only — nothing is waited on**, so it is safe from a report's
     * completion callback on the main thread. It used to block until every
     * delete, marker cleanup, snapshot write and directory scan had finished,
     * which on wedged storage is an ANR (Codex, PR #4). Nothing depends on the
     * work being done by the time this returns: the worker is FIFO, so anything
     * submitted after it still sees the consumed world. That is the same reason
     * [acknowledgeCrashBanner] does not wait, and it is why [readPreviousRun] is
     * the odd one out — it has an answer to give back.
     */
    fun clearPreviousRun(run: PreviousRun) {
        runCatching {
            worker.execute {
                var resumed = false
                val undeleted = mutableListOf<File>()
                run.files.toList().forEach { file ->
                    if (!discardContents(file)) {
                        undeleted += file
                        return@forEach
                    }
                    if (file == current) {
                        // The run the stand-down was protecting has now been
                        // shared and discarded, so there is nothing left to
                        // overwrite — but only once the marker goes with it.
                        if (consumeCrashMarker()) {
                            resumeMirroring()
                            resumed = true
                        } else {
                            // Writing this run to `debug.log` beside a marker
                            // that could not be consumed would have the next
                            // start read an ordinary run as a crash and raise a
                            // banner for it (Codex, PR #4). Staying down costs
                            // this run's file; resuming would cost the truth.
                            log.warning(
                                "This run will not be saved: an earlier crash marker could not be cleared",
                            )
                        }
                    }
                }
                // Kept, not forgotten: a file dropped from this list while its
                // contents survive is a report the user has already sent riding
                // the next one as well, with nothing to say so (Codex, PR #4).
                // Holding it means the next share retries the discard.
                if (undeleted.isNotEmpty()) {
                    log.warning("%s shared run(s) could not be discarded", undeleted.size)
                }
                run.files.clear()
                run.files += undeleted
                // Nothing left for a dismissal's rename to follow into, so stop
                // carrying it. A handle still holding an undeleted file stays,
                // because the next share retries that discard through it.
                if (run.files.isEmpty()) outstanding.removeAll { it.get() === run }
                // Clearing the flag only lets *future* writes through, and this
                // run has been accumulating in the buffer with none of them
                // landing. A silent kill before the next entry would lose the
                // whole run even though the obstruction is gone (Codex, PR #4).
                if (resumed) writeSnapshot()
                // The second of the three points the crash record changes: a
                // share consumed the crashed runs along with the rest.
                recomputeUnacknowledgedCrash()
            }
        }.onFailure { log.failure(it, "A shared run could not be cleared") }
    }

    // ---------------------------------------------------------- crash banner

    /** Notified, on the worker, whenever [unacknowledgedCrash] changes. */
    fun interface CrashListener {
        fun onUnacknowledgedCrashChanged(unacknowledged: Boolean)
    }

    /**
     * One `addCrashListener` call.
     *
     * The initial delivery is queued against *this registration* rather than
     * against the listener, so the same instance added, removed and added again
     * while the worker is busy is delivered to once — for the registration that
     * is still live — instead of once per queued task, which handed the second
     * registration its promised first value twice (Codex, PR #4). Asking only
     * whether the listener is currently in the list cannot tell those apart.
     */
    private class Registration<L : Any>(val listener: L) {
        @Volatile
        var live = true

        /**
         * Worker-only: whether *this* observer threw last time, so a repeat stays
         * quiet. Per registration rather than per sink — see the publication
         * loop for what a shared latch swallowed.
         */
        var failing = false

        /**
         * Worker-only: whether this registration has been handed any value yet.
         *
         * It is in the list the moment it is made, so a change already queued
         * ahead of the initial-delivery task notifies it — and the task then
         * handed it the same value again, so a screen ran its effects twice for
         * one change (Codex, PR #4). The first value goes out once, from
         * whichever of the two reaches it first.
         */
        var notified = false
    }

    private val crashListeners = CopyOnWriteArrayList<Registration<CrashListener>>()

    /**
     * Guards the check-and-add and the mark-and-remove, which
     * `CopyOnWriteArrayList` makes individually safe but not atomic together.
     * Two threads adding the same listener could both pass the membership check
     * before either appended, leaving two live registrations and two of every
     * notification; and an add could observe a registration a concurrent removal
     * then dropped, returning with the listener registered nowhere (Codex,
     * PR #4). The list's own `addIfAbsent` used to supply this, and identity
     * matching is what replaced it. Reads — the publication loop — stay
     * lock-free on the worker.
     */
    private val registry = Any()

    /**
     * Registers [listener] against [listeners] and queues its first value.
     *
     * Shared by every signal this sink publishes, deliberately: registration
     * identity, liveness, delivery-once and the per-registration spell are four
     * separate races that were each found and fixed on the crash banner (Codex,
     * PR #4), and a second signal reimplementing "roughly that" would reopen
     * whichever of them it got subtly wrong. One implementation, one suite.
     */
    private fun <L : Any, V> addListener(
        listeners: CopyOnWriteArrayList<Registration<L>>,
        listener: L,
        currentValue: () -> V,
        deliver: (L, V) -> Unit,
        threwMessage: String,
    ) {
        val registration = synchronized(registry) {
            if (listeners.any { it.listener === listener }) return
            Registration(listener).also { listeners += it }
        }
        // Delivered rather than assumed. `start()` runs in `Application.onCreate`
        // and publishes there, so by the time a screen registers the value is
        // usually already settled — and publication notifies only on a *change*,
        // so a later recompute deriving the same answer sends nothing. A
        // listener-backed UI starting at the initial value would then miss a
        // state that was already set until it changed twice (Codex, PR #4). On
        // the worker, like every other delivery, so that contract holds here too.
        runCatching {
            worker.execute {
                // Still this registration? A screen can register and go away
                // before the worker gets here, and the remove promised to stop
                // notifying it — delivering anyway can drive disposed UI
                // (Codex, PR #4).
                if (!registration.live) return@execute
                // A publication queued ahead of this may already have given it
                // one, and one is what was promised. See [Registration.notified].
                if (registration.notified) return@execute
                registration.notified = true
                // Reported like any other delivery, and on the same spell: an
                // observer that throws on its first value fails exactly as
                // silently as one that throws on its tenth.
                runCatching { deliver(listener, currentValue()) }
                    .onFailure { failure ->
                        registration.failing = beginSpell(registration.failing) {
                            log.failure(failure, threwMessage)
                        }
                    }
                    .onSuccess { registration.failing = false }
            }
        }.onFailure { log.failure(it, "A listener's first value could not be scheduled") }
    }

    /** Shared by every signal; see [addListener]. */
    private fun <L : Any> removeListener(
        listeners: CopyOnWriteArrayList<Registration<L>>,
        listener: L,
    ) {
        synchronized(registry) {
            // Marked before it is dropped, so a delivery already queued against
            // this registration sees it gone even between the two steps.
            listeners.forEach { if (it.listener === listener) it.live = false }
            listeners.removeIf { it.listener === listener }
        }
        // No spell to clear: it lives on the registration, which has just gone
        // with it. The reset that used to be enqueued here could not have worked
        // anyway — a first delivery queued before it ran under the old flag.
    }

    /**
     * Worker-only. Hands [value] to every registration that is owed it — one
     * whose value [changed], **and any still owed its first**, whether it
     * changed or not.
     *
     * Registration promises one delivery, and the task that carries it can be
     * refused: the listener then sits registered with nothing sent, and since
     * publication is change-only, a later recompute deriving the same answer
     * sent nothing either, so a screen opened over an already-set state missed
     * it until the state happened to change (Codex, PR #4). This is the retry,
     * and it costs nothing when there is none owed. Each failure contained.
     */
    private fun <L : Any, V> publish(
        listeners: CopyOnWriteArrayList<Registration<L>>,
        changed: Boolean,
        value: V,
        deliver: (L, V) -> Unit,
        threwMessage: String,
    ) {
        for (registration in listeners) {
            // Told already, and nothing has changed since: there is nothing this
            // pass owes it. `notified` is what separates the two reasons to be
            // here, so a first delivery is never sent twice.
            if (!changed && registration.notified) continue
            // The list iterates a snapshot, so one removed while a slow listener
            // ahead of it was still running is still in this loop — and would be
            // notified after the remove returned, driving disposed UI (Codex,
            // PR #4). The same question the queued first value asks.
            if (!registration.live) continue
            // Before the call, not after: a listener that throws was still handed
            // a value, and its queued first delivery must not stand in for one.
            registration.notified = true
            runCatching { deliver(registration.listener, value) }
                .onFailure { failure ->
                    // Isolated, so one observer cannot stop the next -- but not
                    // unsaid: an observer that throws stops updating, and the
                    // screen it was driving then shows a value that has quietly
                    // stopped moving, with nothing to explain why (Codex, PR #4).
                    // Once per spell, since a listener that throws once tends to
                    // throw on every change, and reporting fans out to this sink.
                    // Per *registration*, not per sink: a shared latch let one
                    // observer's spell swallow a different observer's very first
                    // failure, and the ordering that produced it was ordinary —
                    // a first delivery queued ahead of the removal that would
                    // have cleared the flag (Codex, PR #4). A spell is about one
                    // broken observer, so it belongs to that observer.
                    registration.failing = beginSpell(registration.failing) {
                        log.failure(failure, threwMessage)
                    }
                }
                .onSuccess { registration.failing = false }
        }
    }

    @Volatile
    private var unacknowledgedCrashValue = false

    /**
     * Whether a prior run crashed and the user has neither shared nor dismissed
     * its log — the post-crash banner's whole state, **derived rather than
     * maintained**.
     *
     * Nothing outside this class writes it, and nothing inside it writes it off
     * the worker. It is recomputed there at each of the three points where the
     * crash record actually changes: the startup rotation, a share's clear, and
     * a dismissal's rename. Callers only observe, via this property or
     * [addCrashListener].
     *
     * That is the fix for a family of races rather than a style preference. While
     * this was a separate flag that screens read from disk and wrote back, every
     * screen was a writer — and two screens can exist at once. A screen could
     * then write a stale answer over a newer one: re-raising a banner the user
     * had just dismissed, or re-offering a log a share had already consumed,
     * which invites a report carrying no crash to be copied over the one that had
     * it. Guarding each interleaving in turn kept exposing the next. Deriving the
     * value from the state it describes, on the worker that owns that state,
     * leaves no interleaving to guard.
     *
     * **Deliberately not a `StateFlow`.** This library takes no third-party
     * runtime dependency (AGENTS.md), and a coroutines dependency would reach
     * every consumer's APK to deliver one boolean. A consumer wraps
     * [addCrashListener] in its own `MutableStateFlow` in three lines, which is
     * where the flow belongs anyway — the derivation is the part that had to be
     * shared, not the delivery.
     */
    val unacknowledgedCrash: Boolean get() = unacknowledgedCrashValue

    /**
     * Observes [unacknowledgedCrash]. The listener fires on the worker thread,
     * only when the value actually changes, so it must not block; post to
     * wherever it is being rendered.
     *
     * Registering delivers the current value once, so an observer never has to
     * assume one. It does not itself *recompute* — call [requestCrashRecompute]
     * when a screen starts observing, which is the moment the answer has to be
     * re-derived from what is actually on disk.
     */
    fun addCrashListener(listener: CrashListener) {
        addListener(
            listeners = crashListeners,
            listener = listener,
            currentValue = { unacknowledgedCrashValue },
            deliver = CrashListener::onUnacknowledgedCrashChanged,
            threwMessage = "A crash-state listener threw",
        )
    }

    /**
     * Stops [listener] being notified.
     *
     * **One delivery may already be in flight when this returns**, and closing
     * that window is deliberately not attempted (Codex, PR #4). Every delivery
     * checks the registration is live and then calls the listener, and a removal
     * can land between those two steps; making it impossible means holding the
     * removal until the in-flight call finishes, which blocks the caller — the
     * main thread, at `onDestroy` — on an arbitrary listener running on the
     * worker. That is the ANR shape this sink took out of `clearPreviousRun`,
     * traded for a window a listener can close itself by ignoring a value after
     * it has stopped observing. Written up in `TODO.md`.
     */
    fun removeCrashListener(listener: CrashListener) {
        removeListener(crashListeners, listener)
    }

    /**
     * Asks for the crash state to be re-derived, and returns at once.
     *
     * Call it when a screen starts observing. That is the moment the answer has
     * to be right, and it is the retry that keeps a failed read from being
     * permanent: the value starts false and a failed listing leaves it alone, so
     * a startup read that could not list the directory would otherwise render
     * "couldn't look" as "no crash" for the life of the process.
     *
     * Asking is not the same as the caller maintaining the value. The sink still
     * does the read and the write, on its own worker, so a request can never
     * apply a stale answer over a newer one — the property the whole derived
     * design rests on. Only the enqueue happens on the caller's thread, so this
     * is safe from a composition or a view model's construction.
     *
     * Deliberately *not* run off every log write. Doing that put a directory scan
     * on the worker on every logged line, where it can sit ahead of the crash
     * handler's fatal flush and spend a budget meant for landing the crash log. A
     * screen opening is both rarer and the only time the answer is observed.
     */
    fun requestCrashRecompute() {
        runCatching { worker.execute { recomputeUnacknowledgedCrash() } }
            .onFailure { log.failure(it, "Crash-banner check could not be scheduled") }
    }

    /** Worker-only: whether the last crash check failed, so the next repeat stays quiet. */
    private var crashCheckFailing = false

    /**
     * Recomputes [unacknowledgedCrash]. **Worker only** — it reads the directory
     * and has to stay ordered against every other change to it.
     *
     * A listing that could not be read leaves the value alone. Lowering the
     * banner on an unreadable answer would retire the only offer to send a crash
     * log that is still on disk, and the banner does not come back for that
     * crash; leaving it up costs at most one redundant offer.
     *
     * The failure is reported once per spell, not once per attempt — which is
     * load-bearing rather than tidiness. This runs where a failure is likely to
     * repeat, and recording fans out to *this* sink, whose [log] queues another
     * snapshot write: reporting on every attempt would mean recompute, report,
     * queue, recompute, for as long as the directory stayed unlistable — a
     * self-sustaining loop of directory scans and failed writes on the worker.
     * One line is also the right amount of signal: a reader needs to know the
     * check stopped working, not how many times it retried.
     */
    private fun recomputeUnacknowledgedCrash() {
        // The read itself can throw, not just answer null: resolving the cache
        // directory is lazy and happens here. Letting that escape would leave the
        // value at its initial false with nothing recorded — the silent "no crash"
        // this whole design exists to remove.
        val scan = runCatching { scanForCrashes() }
            .onFailure { failure ->
                crashCheckFailing = beginSpell(crashCheckFailing) {
                    log.failure(failure, "Crash-banner check failed")
                }
            }
            .getOrNull()
        if (scan == null) {
            crashCheckFailing = beginSpell(crashCheckFailing) {
                log.warning("Crash-banner check could not list the log directory")
            }
            return
        }
        // A crash it *could* read outranks one it could not: the banner goes up
        // either way, and going up is never the unsafe direction.
        if (scan.crashed.isNotEmpty()) {
            crashCheckFailing = false
            publishUnacknowledgedCrash(true)
            return
        }
        // Otherwise an entry that would not say leaves the answer exactly as it
        // is. Publishing false here lowers a banner that is already up, and the
        // next recompute may not come before the user's chance to report is gone
        // (Codex, PR #4).
        if (scan.unknown) {
            crashCheckFailing = beginSpell(crashCheckFailing) {
                log.warning("Crash-banner check could not read a prior run, so the banner is unchanged")
            }
            return
        }
        crashCheckFailing = false
        publishUnacknowledgedCrash(false)
    }

    /**
     * Worker-only, without exception — that is the invariant the derived design
     * rests on, and the one place that broke it (an eager lowering on a
     * dismissal's calling thread) raced a recompute for exactly that reason.
     * Notifies on a change — **and any registration still owed its first
     * value**, whether the value changed or not. Registration promises one
     * delivery, and the task that carries it can be refused: the listener then
     * sits registered with nothing sent, and since publication is
     * change-only, a `requestCrashRecompute()` deriving the same answer sent
     * nothing either, so a screen opened over an already-raised banner missed
     * it until the state happened to change (Codex, PR #4). This is the retry,
     * and it costs nothing when there is none owed. Each failure contained.
     */
    private fun publishUnacknowledgedCrash(value: Boolean) {
        val changed = unacknowledgedCrashValue != value
        if (changed) unacknowledgedCrashValue = value
        publish(
            listeners = crashListeners,
            changed = changed,
            value = unacknowledgedCrashValue,
            deliver = CrashListener::onUnacknowledgedCrashChanged,
            threwMessage = "A crash-state listener threw",
        )
    }

    /**
     * Worker-only: does either reason for the stand-down still hold?
     *
     * A rotation that *succeeded* and then could not consume the marker used to
     * be a stand-down with no way out. `debug.log` is gone, so there is no
     * retained run for a share to clear, and the resume in [clearPreviousRun]
     * only fires for `debug.log` itself — so this process never persisted
     * another line, even once the obstruction was gone (Codex, PR #4).
     *
     * Asked before every write instead. The marker is *retried* rather than
     * re-reported: the failure was said once where it was decided, and a line
     * per write for as long as an obstruction lasts is the loop the spells
     * exist to prevent. The cost while stood down is one metadata delete
     * attempt per debounced write, and it stops the moment it succeeds.
     */
    private fun standDownStillNeeded(): Boolean {
        // A run the rotation left in `debug.log` is still there to protect, and
        // only a share ends that — [clearPreviousRun] owns the resume for it.
        if (retainedPreviousRun() != null) return true
        // So the rotation moved it, and all that can be left is the marker,
        // which would hand this run the crash suffix at the next start.
        return !discardContents(crashMarker)
    }

    /**
     * Dismiss: rename every crashed prior-run file off the crash suffix so it no
     * longer raises the banner, keeping its log still shareable in a report.
     *
     * Renaming in place — rather than recording a separate acknowledgement marker
     * in this evictable cache directory — so cache eviction cannot resurrect the
     * prompt by dropping the marker while keeping the crash file. A later crash
     * writes a fresh crash-suffixed file and raises the banner again.
     */
    fun acknowledgeCrashBanner() {
        // Nothing is waited on. This is called straight from a tap, and the
        // rename would otherwise queue behind an in-flight write — so waiting for
        // it would block the main thread on storage and can ANR. Only the enqueue
        // happens on the caller's thread.
        //
        // Nothing is *published* on the caller's thread either, and an earlier
        // revision got that wrong: it lowered the banner eagerly, before the
        // worker, so the user would not watch it linger. That put a write to the
        // state and a listener fan-out on the calling thread, which is the one
        // thing the derived design rests on not happening — and it raced a
        // recompute already running, which could publish `true` after the eager
        // `false` and flash the banner back up before the rename lowered it
        // again (Codex, PR #4). It bought very little: this is submitted with no
        // delay, so it runs ahead of any debounced write already scheduled.
        //
        // Losing the eager lowering also deletes the restore-on-failure branch it
        // needed, because a dismissal that reached nothing now simply leaves the
        // recompute below to publish the truth — an unknown check leaves the
        // banner up by construction rather than by a second code path.
        runCatching {
            worker.submit {
                runCatching {
                    scanForCrashes()?.crashed?.forEach { file ->
                        if (file == current) {
                            // The retained run carries no crash suffix to rename
                            // off; what marks it is the marker, so consuming that
                            // is the dismissal. Its log stays, still shareable.
                            // It says so itself when it cannot.
                            consumeCrashMarker()
                            return@forEach
                        }
                        val plain = File(
                            dir,
                            file.name.removeSuffix(PREVIOUS_CRASH_SUFFIX) + PREVIOUS_PLAIN_SUFFIX,
                        )
                        // The plain name can already be taken. Two rotations
                        // sharing a `System.nanoTime()` base do not collide with
                        // each other when their suffixes differ, so a crashed run
                        // and an ordinary one can sit under one base — and
                        // `renameTo` then *replaces* rather than refusing, which
                        // is the same answer-for-two-cases the rotation fixed,
                        // one call site over (Codex, PR #4). What a wrong "free"
                        // destroys is an unshared run, permanently, so unknown
                        // counts as taken here too.
                        //
                        // Refusing leaves the banner up, which is this path's
                        // safe direction and is already what it does for a
                        // refused rename. It does not strand the user: sharing
                        // the report still consumes the run and lowers the
                        // banner, so the dismissal is the blocked route, not the
                        // only one.
                        val free = presenceOf(plain) == false
                        if (!free || !runCatching { file.renameTo(plain) }.getOrDefault(false)) {
                            // The tap did nothing and the banner stays up, which
                            // is the right direction to fail in — but with no
                            // line here the user is left tapping a control that
                            // has no visible effect and no reason (Codex, PR #4).
                            log.warning("A crash could not be dismissed, so its banner stays")
                            return@forEach
                        }
                        // A share already in flight has read this file and is
                        // holding its *old* path to delete once its report lands.
                        // Follow the rename, or that delete misses and a log the
                        // user has already been sent survives to ride the next
                        // report as well — a dismissal landing between
                        // the read and the clear. Same worker as both of those,
                        // so this write is ordered against them.
                        liveHandles().forEach { handle ->
                            val at = handle.files.indexOf(file)
                            if (at >= 0) handle.files[at] = plain
                        }
                    }
                }.onFailure { log.failure(it, "A crash-banner dismissal failed") }
                // The third of the three points the record changes. A rename that
                // did not happen leaves the banner up, which is the direction an
                // unknown check has to fail in.
                recomputeUnacknowledgedCrash()
                // Derived from what that published, and only after it. The
                // outcome is the question the user is actually asking -- I
                // tapped Dismiss, is the banner still there? -- so reading it
                // off the renames instead misses every other way the banner
                // survives: an entry that would not say whether it crashed, and
                // a recompute that could not list the directory at all, both of
                // which leave the value exactly where it was while every rename
                // reported success (Codex, PR #20).
                publishStorageOutcomes(crashDismissalFailed = unacknowledgedCrashValue)
            }
        }.onFailure {
            // The task never ran, so the crash file and the banner both stay
            // and nothing on the worker will say so (Codex, PR #20).
            recordStorageFailureOffWorker(crashDismissalFailed = true)
            log.failure(it, "A crash-banner dismissal could not be scheduled")
        }
    }

    // ------------------------------------------------------- storage outcomes

    /**
     * What the last completed attempt at each maintenance operation the user
     * can *ask for* actually did.
     *
     * Both of these already leave a line in the log. That is not enough on its
     * own, and the reason is specific to each: the opt-out purge reports into a
     * log the user has just turned off, so the line is held until recording
     * comes back and may never land at all; and a refused dismissal leaves the
     * user tapping a control with no visible effect, where the one place they
     * would look for a reason is the screen, not a log they would have to go
     * and read. A caller that cannot see the outcome can only show the
     * operation as having worked.
     *
     * One value rather than two properties, so the change test and the delivery
     * stay single — a third outcome later does not change the listener.
     */
    class StorageOutcomes internal constructor(
        /**
         * Whether the last opt-out left this run's saved log on disk.
         *
         * False when the purge had nothing to do: a prior run the rotation
         * could not move is deliberately kept (see [onCleared]), which is the
         * purge working as designed rather than failing.
         */
        val optOutPurgeFailed: Boolean,
        /**
         * Whether the last [acknowledgeCrashBanner] left the banner up.
         *
         * True when a rename was refused, when the directory could not be
         * listed, and when an entry would not say whether it had crashed —
         * all three end with the banner where it was and the tap with nothing
         * to show for it.
         */
        val crashDismissalFailed: Boolean,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StorageOutcomes &&
                other.optOutPurgeFailed == optOutPurgeFailed &&
                other.crashDismissalFailed == crashDismissalFailed

        override fun hashCode(): Int =
            (if (optOutPurgeFailed) 2 else 0) + (if (crashDismissalFailed) 1 else 0)

        override fun toString(): String =
            "StorageOutcomes(optOutPurgeFailed=$optOutPurgeFailed, " +
                "crashDismissalFailed=$crashDismissalFailed)"
    }

    /** Notified, on the worker, whenever [storageOutcomes] changes. */
    fun interface StorageListener {
        fun onStorageOutcomesChanged(outcomes: StorageOutcomes)
    }

    private val storageListeners = CopyOnWriteArrayList<Registration<StorageListener>>()

    private val storageOutcomesRef =
        AtomicReference(StorageOutcomes(optOutPurgeFailed = false, crashDismissalFailed = false))

    /**
     * Applies one operation's outcome, leaving the other exactly as it is, and
     * answers whether anything changed.
     *
     * A compare-and-set loop rather than a read, build and assign. Both fields
     * live in one value, so two operations completing at once each read the
     * other's field before either wrote and then replaced the whole value —
     * the later write clearing a failure the earlier one had genuinely
     * recorded (Codex, PR #20). Two writers is not hypothetical here: the
     * worker publishes one operation's outcome while a *rejected* operation
     * records its own from the calling thread.
     *
     * The loop rather than `updateAndGet` because the caller needs to know
     * whether the value actually moved, and that lambda can run more than once
     * under contention, so a flag set inside it is not the answer for the
     * application that won.
     */
    private fun applyStorageOutcome(
        optOutPurgeFailed: Boolean? = null,
        crashDismissalFailed: Boolean? = null,
    ): Boolean {
        while (true) {
            val current = storageOutcomesRef.get()
            val next = StorageOutcomes(
                optOutPurgeFailed ?: current.optOutPurgeFailed,
                crashDismissalFailed ?: current.crashDismissalFailed,
            )
            if (next == current) return false
            if (storageOutcomesRef.compareAndSet(current, next)) return true
        }
    }

    /**
     * The last outcome of each operation in [StorageOutcomes], written by the
     * worker that performs them — or, when the worker refused the work
     * outright, by the caller recording that refusal — and only observed from
     * outside. Each write touches one operation's field, under a
     * compare-and-set, so neither can clear the other's.
     *
     * Latched rather than derived, unlike [unacknowledgedCrash], because there
     * is nothing on disk to derive it from: "the file the opt-out meant to
     * remove is still here" is indistinguishable from "there is a file" once
     * the attempt is over. Each field is therefore set by its own operation
     * completing, and cleared by that same operation next succeeding — so a
     * retry that works retires the message the failure put on screen.
     *
     * **Deliberately not a `StateFlow`**, for [unacknowledgedCrash]'s reason:
     * no third-party runtime dependency reaches four APKs to deliver two
     * booleans, and a consumer wraps [addStorageListener] in its own flow.
     *
     * **Read this property, do not rely on the listener alone.** Every signal
     * this sink publishes is delivered from its worker, and a worker that has
     * refused an operation will usually refuse to announce it too — so a
     * failure that is recorded here may never reach a registered listener.
     * That is not particular to this outcome: on a refusing worker
     * [unacknowledgedCrash] cannot be recomputed either, and a listener
     * registering then never receives its first value. Reading this after
     * whatever completion callback the caller already has closes the gap for
     * the case the listener cannot.
     */
    val storageOutcomes: StorageOutcomes get() = storageOutcomesRef.get()

    /**
     * Observes [storageOutcomes]. Fires on the worker thread, only on a change,
     * so it must not block; post to wherever it is being rendered. Registering
     * delivers the current value once, so an observer never has to assume one.
     */
    fun addStorageListener(listener: StorageListener) {
        addListener(
            listeners = storageListeners,
            listener = listener,
            currentValue = { storageOutcomesRef.get() },
            deliver = StorageListener::onStorageOutcomesChanged,
            threwMessage = "A storage-outcome listener threw",
        )
    }

    /** Stops [listener] being notified; see [removeCrashListener] for the window. */
    fun removeStorageListener(listener: StorageListener) {
        removeListener(storageListeners, listener)
    }

    /**
     * Records a failed outcome from a thread that is **not** the worker.
     *
     * Only for a scheduling refusal, where the work never ran and the worker
     * is by definition unavailable to publish from — so without this the file
     * survives an opt-out, or a banner survives a dismissal, while the outcome
     * still reads as its previous value, normally success. `DebugLog` holds
     * the rejection, but recording has just been turned off in the opt-out's
     * case, so that line may never be emitted at all: precisely the gap this
     * outcome exists to close (Codex, PR #20).
     *
     * Two deliberate limits. It only ever sets a field to `true`, so a
     * concurrent worker publication cannot be overwritten with a stale
     * success — and there is no pending one for the refused operation anyway,
     * since its task never ran. And it does **not** notify from here: calling
     * listeners on a tap's own thread is the one invariant the derived design
     * rests on, and an earlier eager lowering broke exactly that and raced a
     * recompute. The notification is attempted on the worker, which may refuse
     * it too; the value stands either way, and a caller re-reading
     * [storageOutcomes] on its own completion callback sees the truth.
     */
    private fun recordStorageFailureOffWorker(
        optOutPurgeFailed: Boolean? = null,
        crashDismissalFailed: Boolean? = null,
    ) {
        val changed = applyStorageOutcome(optOutPurgeFailed, crashDismissalFailed)
        runCatching {
            worker.execute {
                publish(
                    listeners = storageListeners,
                    changed = changed,
                    value = storageOutcomesRef.get(),
                    deliver = StorageListener::onStorageOutcomesChanged,
                    threwMessage = "A storage-outcome listener threw",
                )
            }
        }.onFailure {
            // Usually the same refusal again, since the worker that rejected
            // the operation is the one being asked to announce it. Said rather
            // than dropped: an observer that stops updating without a word is
            // what this whole signal exists to stop. [storageOutcomes] carries
            // the answer either way -- see its own note on reading it.
            log.failure(it, "A storage outcome could not be delivered to listeners")
        }
    }

    /**
     * Worker-only. Each argument defaults to what is already published, so an
     * operation states its own outcome and leaves the other alone.
     */
    private fun publishStorageOutcomes(
        optOutPurgeFailed: Boolean? = null,
        crashDismissalFailed: Boolean? = null,
    ) {
        val changed = applyStorageOutcome(optOutPurgeFailed, crashDismissalFailed)
        publish(
            listeners = storageListeners,
            changed = changed,
            value = storageOutcomesRef.get(),
            deliver = StorageListener::onStorageOutcomesChanged,
            threwMessage = "A storage-outcome listener threw",
        )
    }

    // ------------------------------------------------------------------ crash

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // Recorded through the ordinary path, so the crash line is in the
                // buffer the snapshot below writes. This is the dying thread, not
                // the fan-out, so recording here is not nested.
                log.failure(throwable, "Uncaught exception in thread %s", thread.name)
                flushForCrash()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Writes the crash marker and the freshest snapshot, waiting a bounded time
     * for both. Public for an app that installs its own uncaught-exception
     * handler ([start] with `installCrashHandler = false`) and chains this in.
     *
     * The wait exists because the daemon worker's task can be dropped at process
     * death, so a fire-and-forget enqueue could lose the crash snapshot — but it
     * is bounded by [CRASH_WRITE_TIMEOUT_MS] so it never delays the rest of the
     * chain on stalled storage. The atomic temp-then-rename means a write killed
     * at the deadline still leaves the prior complete snapshot intact.
     */
    fun flushForCrash() {
        runCatching {
            val flush = worker.submit {
                // Nothing to save, so nothing to do. With recording off the
                // buffer is already empty and `debug.log` already purged, so the
                // snapshot would write an empty file and the marker would
                // classify a run that does not exist -- which is the decoy the
                // stand-down branch below refuses to file, for the same reason.
                // It also spends [CRASH_WRITE_TIMEOUT_MS] and runs [onCrash],
                // whose every log call the closed gate discards: diagnostic work
                // done on the user's behalf after they turned diagnostics off
                // (Codex, PR #4).
                if (!log.isRecording) return@submit
                // Mandatory first, optional second, in that order and each in its
                // own runCatching. [onCrash] is the optional half: the fatal being
                // handled may be an OutOfMemoryError that reading the app's state
                // would raise again, and a shared runCatching would take the marker
                // and the snapshot down with it.
                // Asked here rather than left to [writeSnapshot], which asks it
                // too: recovering only in there would skip the marker on this
                // branch and then persist the crashing run anyway, so the next
                // start would read a real crash as an ordinary kill and raise no
                // banner for it (Codex, PR #4).
                if (mirroringStoodDown && !standDownStillNeeded()) resumeMirroring()
                if (mirroringStoodDown) {
                    // The marker classifies whatever is in `debug.log`, and while
                    // the stand-down holds that file is the *previous* run rather
                    // than this one. Writing it would hand the crash suffix to a
                    // run that did not crash, and offer it as the evidence — while
                    // the run that actually crashed is not being persisted at all
                    // (Codex, PR #4). Better to say so than to file a decoy.
                    log.warning("This run crashed while an earlier one was still in place, so it was not saved")
                } else {
                    runCatching { crashMarker.writeText("1") }
                        // Contained, but not unsaid: without the marker the next
                        // start reads this crash as an ordinary kill, raises no
                        // banner, and offers nothing to explain the difference.
                        .onFailure { log.failure(it, "The crash could not be marked") }
                }
                // Isolated so the snapshot still happens, and reported so the
                // report does not silently lack the state it was meant to carry.
                runCatching { onCrash() }
                    .onFailure { log.failure(it, "The crash hook failed") }
                writeSnapshot()
            }
            // The wait stays bounded — the handler chain must not be held up —
            // but a task that threw or ran out of time means the marker or the
            // snapshot may be missing, and chaining on without saying so leaves
            // nothing to explain it (Codex, PR #4). The line reaches logcat and
            // the in-memory buffer even where the file it is about did not land.
            runCatching { flush.get(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .onFailure { log.failure(it, "The crash flush did not complete") }
        }.onFailure { log.failure(it, "The crash flush could not be scheduled") }
    }

    /**
     * Blocks until the worker has drained its queue, including the rotation.
     *
     * A test seam, and public because the consuming apps need it as much as
     * this repository's own tests do: everything this sink does to a file
     * happens on one worker thread, so a consumer's test that writes a line and
     * then asserts on the file is asserting against a race unless it can wait
     * for that worker. The alternatives a consumer is left with otherwise are
     * both worse — a sleep, which is the flake this fleet's testing rules
     * forbid outright, or [readPreviousRun] used as an accidental barrier,
     * which drains the queue only as a side effect of doing something else
     * entirely.
     *
     * Not for production code: nothing on a running device should be waiting on
     * the log's worker, which is the whole reason the worker exists.
     */
    fun awaitIdle() {
        // The mirror write is *scheduled*, not queued: it becomes eligible
        // `writeDebounceMs` from now, while a barrier submitted with no delay
        // is eligible immediately and would be run first. Waiting on the
        // barrier alone therefore returns before the write the caller is
        // actually waiting for -- 500 ms before it, in a consumer's app, which
        // is exactly the caller this method exists for (Codex, PR #18).
        //
        // So end the window early instead of waiting it out: run the write's
        // own body on the worker now, for the same token that
        // gates it. Whichever of the two runs first does the write and clears
        // the flag, and the other finds it clear and does nothing -- so there
        // is no cancellation to race, and no held `ScheduledFuture` that a
        // slow scheduling thread could publish over a newer one (Codex,
        // PR #18).
        //
        // One forced window is enough, and that turns on an ordering worth
        // stating: [runDebouncedWrite] reports what the window held -- a purge
        // failure, a stand-down, a refused scheduling -- *before* it reads the
        // snapshot, so each of those complaints is already in the buffer this
        // same write persists. Recording them does schedule a further window,
        // but that window has nothing left to add, so draining until the worker
        // is quiescent would buy nothing -- and a write that keeps failing keeps
        // reporting, which makes the drain a cycle rather than a wait (Codex,
        // PR #18).
        //
        // What this cannot cover is a line logged by the *caller* after the
        // call began, which is the caller's own ordering to get right -- nor a
        // write that fails outright, whose own failure is reported after the
        // snapshot was read. The file is stale then because the write failed,
        // which no amount of waiting fixes.
        worker.submit { writeToken.get()?.let { runDebouncedWrite(it) } }.get()
        worker.submit {}.get()
    }
}

