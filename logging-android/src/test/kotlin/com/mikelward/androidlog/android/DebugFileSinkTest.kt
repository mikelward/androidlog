package com.mikelward.androidlog.android

import com.mikelward.androidlog.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The file sink, asserted in both directions: that a crashed run raises the
 * banner *and* that an ordinary kill does not, that a failed listing is
 * reported *and* that it does not lower the banner.
 *
 * Everything here runs on a plain JVM — no Robolectric, no device — which is
 * why the sink takes a [File] rather than a `Context`.
 */
class DebugFileSinkTest {

    private val dir: File = Files.createTempDirectory("androidlog").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun log() = DebugLog(readMillis = { 0L })

    /**
     * `awaitIdle` is the seam a consuming app's tests wait on, and its
     * visibility is the whole of what it promises. A test *inside* this module
     * cannot tell the difference — `internal` is visible here either way — so
     * this asks the question a consumer asks: is there a public method by that
     * name on the class? Kotlin mangles an internal function's JVM name, so
     * narrowing the visibility back makes this throw rather than pass quietly,
     * which is the only way the disappearance surfaces before it surfaces as
     * four apps failing to compile.
     */
    @Test
    fun `awaitIdle is public so a consuming app's tests can wait on the worker`() {
        val method = DebugFileSink::class.java.getMethod("awaitIdle")
        assertTrue(
            "awaitIdle is not public on the JVM class consumers compile against",
            java.lang.reflect.Modifier.isPublic(method.modifiers),
        )
    }

    private fun sink(log: DebugLog = log(), at: File = dir) = DebugFileSink(log, at)

    /**
     * The promise [DebugFileSink.awaitIdle] makes, under the debounce a
     * consuming app actually runs with.
     *
     * The mirror write is *scheduled* rather than queued, so with a real
     * debounce a barrier submitted with no delay is eligible first and the
     * wait returns before the write it was supposed to cover (Codex, PR #18).
     * A debounce of zero hides that entirely — every other test here uses one,
     * which is why none of them caught it — so this one uses a window far
     * longer than the assertion could outlast by chance: if `awaitIdle` did not
     * bring the write forward, the file is still absent or empty when it
     * returns, and a ten-second wait cannot be mistaken for a slow machine.
     */
    @Test
    fun `awaitIdle covers a write still inside its debounce window`() {
        val log = log()
        val sink = DebugFileSink(log, { dir }, 10_000L, {}, { "1" })
        log.addSink(sink)
        log.event("armed at %s", 3)
        sink.awaitIdle()
        assertTrue(
            "awaitIdle returned before the debounced write landed",
            current().exists() && current().readText().contains("armed at 3"),
        )
    }

    /**
     * A forced write reports what its window held, and reporting records a
     * line — so the obvious worry is that the complaint explaining why the
     * file is short lands only in the *next* window, which `awaitIdle` would
     * return ahead of (Codex, PR #18).
     *
     * It does not, and this pins the ordering that makes it not: the window
     * reports **before** it reads the snapshot, so its own complaints are in
     * the buffer that this same write persists. Reordering the report after
     * the read — or moving it out of the window — fails this, which is the
     * point of asserting it rather than reasoning about it again later.
     *
     * Arranged through the one complaint that needs no broken filesystem: a
     * worker that refuses the first scheduling, held and said by the first
     * write that does land. The debounce is a real one, so the write under
     * test is genuinely the forced one.
     */
    @Test
    fun `a complaint the forced write records lands in the file it explains`() {
        val log = log()
        val sink = DebugFileSink(
            log,
            { dir },
            10_000L,
            {},
            { "1" },
            { RefusesFirstSchedule(ScheduledThreadPoolExecutor(1)) },
        )
        log.addSink(sink)

        log.event("the entry whose write was refused")
        log.event("the entry whose write lands")
        sink.awaitIdle()

        val text = current().readText()
        assertTrue(text, text.contains("the entry whose write lands"))
        assertTrue(
            "the complaint the forced write recorded is missing from the file it explains",
            text.contains("could not be scheduled, so this run"),
        )
    }

    /**
     * And the debounce still coalesces afterwards: bringing one write forward
     * must not strand the pending flag, or `log` would never schedule another
     * and the mirror would go silently stale for the life of the process.
     */
    @Test
    fun `a write after awaitIdle is still scheduled`() {
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, {}, { "1" })
        log.addSink(sink)
        log.event("first %s", 1)
        sink.awaitIdle()
        log.event("second %s", 2)
        sink.awaitIdle()
        assertTrue(current().readText(), current().readText().contains("second 2"))
    }

    /** A sink whose rotation destination is a name the test can arrange first. */
    private fun sinkRotatingTo(name: String, log: DebugLog = log()) =
        DebugFileSink(log, { dir }, 0L, {}, { name })

    /** Mirrors `MAX_PREVIOUS_RUNS`, which is private to the sink. */
    private val MAX_KEPT = 5

    private fun current() = File(dir, "androidlog.log")

    private fun previous() = dir.listFiles { file -> file.name.startsWith("androidlog-prev-") }
        ?.sortedBy { it.name }
        ?: emptyList()

    // ------------------------------------------------------------- mirroring

    @Test
    fun `a recorded entry reaches the current file`() {
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("armed at %s", 3)
        sink.awaitIdle()
        assertTrue(current().readText(), current().readText().contains("armed at 3"))
    }

    @Test
    fun `each write replaces the file rather than appending`() {
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("first")
        sink.awaitIdle()
        log.event("second")
        sink.awaitIdle()
        // One occurrence, not two: the mirror is a full replacement of the
        // buffer, so an entry already in it must not be written again.
        assertEquals(1, current().readText().split("first").size - 1)
        assertTrue(current().readText().contains("second"))
    }

    @Test
    fun `the write leaves no temp file behind`() {
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("armed")
        sink.awaitIdle()
        // The temp is renamed over the current file, not left alongside it —
        // otherwise the atomic replace would leak a file per write.
        assertFalse(File(dir, "androidlog.log.tmp").exists())
    }

    @Test
    fun `mirroring does not record from inside the fan-out`() {
        // A sink that records inside `log()` has that entry dropped and reported
        // (DebugLog, PR #3). This one must enqueue instead, so no notice appears.
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        repeat(3) { log.event("event %s", it) }
        sink.awaitIdle()
        log.event("after")
        assertFalse(log.snapshot().toString(), log.snapshot().any { it.contains("dropped") })
    }

    // -------------------------------------------------------------- rotation

    @Test
    fun `the previous run is rotated aside and readable at the next start`() {
        current().writeText("02-01 10:00:00.000 D the run before\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertFalse("the current file is renamed, not copied", current().exists())
        assertEquals(1, previous().size)
        assertTrue(sink.readPreviousRun()!!.text.contains("the run before"))
    }

    @Test
    fun `only the newest runs are kept`() {
        repeat(8) { index ->
            File(dir, "androidlog-prev-$index.log").writeText("run $index\n")
            // Distinct last-modified times, since the prune sorts on them.
            File(dir, "androidlog-prev-$index.log").setLastModified(1_000L + index * 1_000L)
        }
        current().writeText("newest\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val kept = previous().map { it.readText() }
        assertEquals(5, kept.size)
        assertFalse(kept.toString(), kept.any { it.contains("run 0") })
        assertTrue(kept.toString(), kept.any { it.contains("newest") })
    }

    @Test
    fun `a share consumes the previous runs`() {
        current().writeText("the run before\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val shared = sink.readPreviousRun()!!
        assertTrue(shared.text.contains("the run before"))
        sink.clearPreviousRun(shared)
        sink.awaitIdle()
        assertEquals(0, previous().size)
        assertNull("a consumed run is not offered twice", sink.readPreviousRun())
    }

    @Test
    fun `a run that could not be read is left in place rather than destroyed`() {
        // A directory where a log file is expected cannot be read as text, so it
        // stands in for any unreadable prior run.
        File(dir, "androidlog-prev-1.log").mkdirs()
        File(dir, "androidlog-prev-2.log").writeText("readable\n")
        val sink = sink()

        val shared = sink.readPreviousRun()!!
        assertTrue(shared.text.contains("readable"))
        sink.clearPreviousRun(shared)
        sink.awaitIdle()
        assertTrue("the unread run survives its own share", File(dir, "androidlog-prev-1.log").exists())
        assertFalse(File(dir, "androidlog-prev-2.log").exists())
    }

    // ----------------------------------------------------------------- crash

    @Test
    fun `a crashed run raises the banner and an ordinary kill does not`() {
        // Both directions in one test, because the whole point of the marker is
        // telling them apart: a routine kill leaves a prior-run file too.
        current().writeText("was killed\n")
        val killed = sink()
        killed.start(installCrashHandler = false)
        killed.awaitIdle()
        assertFalse("a silent kill is not a crash", killed.unacknowledgedCrash)
        assertTrue(previous().single().name.endsWith(".log"))
        assertFalse(previous().single().name.endsWith(".crash.log"))

        dir.deleteRecursively()
        dir.mkdirs()
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val crashed = sink()
        crashed.start(installCrashHandler = false)
        crashed.awaitIdle()
        assertTrue("an uncaught exception is", crashed.unacknowledgedCrash)
        assertTrue(previous().single().name.endsWith(".crash.log"))
    }

    @Test
    fun `the crash marker is consumed so it cannot mislabel the next run`() {
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val crashed = sink()
        crashed.start(installCrashHandler = false)
        crashed.awaitIdle()
        assertFalse("the marker is read once, then consumed", File(dir, "androidlog.log.crash").exists())

        // Share it away, so what the next start sees is only its own run.
        crashed.clearPreviousRun(crashed.readPreviousRun()!!)
        crashed.awaitIdle()

        // A second run that ended ordinarily leaves an ordinary file behind: the
        // consumed marker cannot make it look like a crash.
        current().writeText("ordinary\n")
        val next = sink()
        next.start(installCrashHandler = false)
        next.awaitIdle()
        assertTrue(previous().single().name.endsWith(".log"))
        assertFalse(previous().single().name.endsWith(".crash.log"))
        assertFalse(next.unacknowledgedCrash)
    }

    @Test
    fun `dismissing lowers the banner and keeps the log shareable`() {
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue(sink.unacknowledgedCrash)

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertFalse(sink.unacknowledgedCrash)
        // Renamed off the crash suffix rather than deleted: dismissing the
        // banner must not throw away the log it was offering.
        assertTrue(previous().single().name.endsWith(".log"))
        assertFalse(previous().single().name.endsWith(".crash.log"))
        assertTrue(sink.readPreviousRun()!!.text.contains("crashed"))
    }

    @Test
    fun `a dismissal onto a name already taken keeps the run that is there`() {
        // Two rotations sharing a `System.nanoTime()` base do not collide when
        // their suffixes differ, so a crashed run and an ordinary one can sit
        // under one base — and the dismissal renames the crash suffix off,
        // straight onto the other one. `renameTo` replaces rather than refusing
        // (Codex, PR #4).
        File(dir, "androidlog-prev-1.crash.log").writeText("the run that crashed\n")
        File(dir, "androidlog-prev-1.log").writeText("an earlier run, never shared\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue("the premise: the banner is up", sink.unacknowledgedCrash)

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()

        assertEquals(
            "the run already under the plain name is untouched",
            "an earlier run, never shared\n",
            File(dir, "androidlog-prev-1.log").readText(),
        )
        assertEquals(
            "and the crashed one is still there to share",
            "the run that crashed\n",
            File(dir, "androidlog-prev-1.crash.log").readText(),
        )
        assertTrue("so the banner stays up, which is the safe direction", sink.unacknowledgedCrash)
    }

    @Test
    fun `a share lowers the banner too`() {
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        sink.clearPreviousRun(sink.readPreviousRun()!!)
        sink.awaitIdle()
        assertFalse("the crash was consumed along with its log", sink.unacknowledgedCrash)
    }

    @Test
    fun `a dismissal between a read and its clear still deletes the file it renamed`() {
        // The share holds the path it read; the dismissal renames it. Without
        // following the rename the delete misses, and a log the user has already
        // been sent rides the next report as well.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val shared = sink.readPreviousRun()!!
        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        sink.clearPreviousRun(shared)
        sink.awaitIdle()
        assertEquals("the renamed file was still consumed", 0, previous().size)
    }

    @Test
    fun `a listener is notified on a change and not on a repeat`() {
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        val seen = mutableListOf<Boolean>()
        sink.addCrashListener { seen += it }
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        sink.requestCrashRecompute()
        sink.awaitIdle()

        // The leading `false` is the value delivered on registration, before
        // anything had been derived; then one change, not one per recompute.
        assertEquals("one change, not one per recompute", listOf(false, true), seen)

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `a flush writes the crash marker and the freshest snapshot`() {
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("last thing before the end")
        sink.flushForCrash()

        assertTrue(File(dir, "androidlog.log.crash").exists())
        assertTrue(current().readText().contains("last thing before the end"))
    }

    // ------------------------------------------------- an unreadable listing

    /** A path that is a regular file, so `listFiles` answers null everywhere. */
    private fun unlistable(): File = File(dir, "not-a-directory").apply { writeText("x") }

    @Test
    fun `a listing that cannot be read leaves the banner alone and says so once`() {
        val log = log()
        val sink = sink(log, at = unlistable())
        sink.requestCrashRecompute()
        sink.awaitIdle()
        sink.requestCrashRecompute()
        sink.awaitIdle()

        // Reported, because a check that stopped working is exactly what a
        // reader needs to know...
        val complaints = log.snapshot().filter { it.contains("could not list") }
        // ...but once per spell, not once per attempt: recording fans out to
        // this same sink, so a report per attempt is a self-sustaining loop.
        assertEquals(complaints.toString(), 1, complaints.size)
        // Left where it started rather than set from an answer that never came.
        // That an unknown answer cannot *lower* a raised banner is the stronger
        // claim, and `a dismissal that reaches nothing leaves the banner up` is
        // where it is actually made, from a real `true`.
        assertFalse(sink.unacknowledgedCrash)
    }

    @Test
    fun `the complaint returns once the check starts working again`() {
        val log = log()
        val sink = sink(log, at = unlistable())
        sink.requestCrashRecompute()
        sink.awaitIdle()

        // Replace the blocking file with a real directory, so the next check
        // succeeds and the spell ends...
        val path = File(dir, "not-a-directory")
        path.delete()
        path.mkdirs()
        sink.requestCrashRecompute()
        sink.awaitIdle()

        // ...then break it again. A second spell is a second report, because
        // the check failing anew is news.
        path.deleteRecursively()
        path.writeText("x")
        sink.requestCrashRecompute()
        sink.awaitIdle()

        assertEquals(2, log.snapshot().count { it.contains("could not list") })
    }

    // ------------------------------------------------- a rotation that fails

    @Test
    fun `a previous run that cannot be rotated aside is kept, not overwritten`() {
        // Renaming onto an existing directory is refused, and the copy fallback
        // refuses to overwrite it — so the move fails for real rather than by a
        // stub, on any filesystem this runs on. Non-empty, so it is an
        // obstruction the prune cannot tidy away either.
        File(dir, "androidlog-prev-pinned.crash.log/held").mkdirs()
        current().writeText("the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")

        val log = log()
        val sink = sinkRotatingTo("pinned", log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.event("this run, which must not replace it")
        sink.awaitIdle()

        assertTrue(
            "the previous run survives",
            current().readText().contains("the crash nobody has read yet"),
        )
        assertFalse(
            "and is not replaced by this run",
            current().readText().contains("this run, which must not replace it"),
        )
        assertTrue(
            "its crash classification survives too, for the next start to use",
            File(dir, "androidlog.log.crash").exists(),
        )
        assertTrue(
            "and the destination that was already there is not this rotation's to delete",
            File(dir, "androidlog-prev-pinned.crash.log").exists(),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be rotated aside") },
        )
    }

    @Test
    fun `a rotation that failed is retried at the next start`() {
        File(dir, "androidlog-prev-pinned.crash.log").mkdirs()
        current().writeText("the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")
        sinkRotatingTo("pinned").apply { start(installCrashHandler = false) }.awaitIdle()

        // Clear the obstruction, as a later launch on a healthy device would find.
        File(dir, "androidlog-prev-pinned.crash.log").deleteRecursively()
        val next = sink()
        next.start(installCrashHandler = false)
        next.awaitIdle()

        assertTrue("still classified as a crash", next.unacknowledgedCrash)
        assertTrue(next.readPreviousRun()!!.text.contains("the crash nobody has read yet"))
    }

    // --------------------------------------------------------- the opt-out

    @Test
    fun `turning recording off removes the persisted copy`() {
        // Otherwise "off" holds only in memory: the core discards the buffer,
        // this file keeps it, and the next start rotates it into the prior-run
        // set where a report can still pick it up.
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("collected before the opt-out")
        sink.awaitIdle()
        assertTrue(current().exists())

        log.setRecording(false)
        sink.awaitIdle()
        assertFalse("nothing survives the opt-out on disk", current().exists())

        // ...and the next start therefore has nothing to rotate into a share.
        val next = sink()
        next.start(installCrashHandler = false)
        next.awaitIdle()
        assertNull(next.readPreviousRun())
    }

    @Test
    fun `turning recording back on persists again`() {
        // The other direction: the purge must not be a permanent stop.
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("before")
        sink.awaitIdle()
        log.setRecording(false)
        sink.awaitIdle()

        log.setRecording(true)
        log.event("after")
        sink.awaitIdle()
        assertTrue(current().readText().contains("after"))
        assertFalse(current().readText().contains("before"))
    }

    @Test
    fun `the opt-out leaves a previous run alone`() {
        // A prior run may be the crash the user is part way through sending, and
        // turning recording off now is not an instruction to destroy a report
        // already offered. The core clears its buffer, not the whole history.
        current().writeText("an earlier run\n")
        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.addSink(sink)

        log.setRecording(false)
        sink.awaitIdle()
        assertTrue(sink.readPreviousRun()!!.text.contains("an earlier run"))
    }

    // ----------------------- a retained run is a prior run in every sense

    /** Arranges a rotation that cannot move `androidlog.log` aside, and starts. */
    private fun sinkWithFailedRotation(log: DebugLog = log()): DebugFileSink {
        File(dir, "androidlog-prev-pinned.crash.log").mkdirs()
        current().writeText("the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")
        return sinkRotatingTo("pinned", log).apply {
            start(installCrashHandler = false)
            awaitIdle()
        }
    }

    @Test
    fun `a preserved run raises the banner and is shareable during this launch`() {
        // Waiting for a rotation that may never succeed would mean the crash is
        // never offered at all. Mirroring has stood down, so `androidlog.log` holds
        // the previous run and nothing else — it is a prior run in every sense.
        val sink = sinkWithFailedRotation()

        assertTrue("the preserved crash raises the banner", sink.unacknowledgedCrash)
        assertTrue(sink.readPreviousRun()!!.text.contains("the crash nobody has read yet"))
    }

    @Test
    fun `sharing a preserved run lets mirroring resume`() {
        val log = log()
        val sink = sinkWithFailedRotation(log)
        log.addSink(sink)

        sink.clearPreviousRun(sink.readPreviousRun()!!)
        sink.awaitIdle()
        // The file may exist again immediately — resuming writes this run's
        // buffer straight away — so the claim is about its *contents*.
        assertFalse(
            "the shared run is consumed",
            current().exists() && current().readText().contains("the crash nobody has read yet"),
        )
        assertFalse("and the banner falls with it", sink.unacknowledgedCrash)

        // Nothing is left to overwrite, so this run is persisted again.
        log.event("this run, now that there is room for it")
        sink.awaitIdle()
        assertTrue(current().readText().contains("this run, now that there is room for it"))
    }

    @Test
    fun `dismissing a preserved run keeps its log and lowers the banner`() {
        val sink = sinkWithFailedRotation()

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertFalse(sink.unacknowledgedCrash)
        assertTrue("the log stays shareable", sink.readPreviousRun()!!.text.contains("the crash nobody has read yet"))
    }

    @Test
    fun `the opt-out leaves a preserved run alone`() {
        // The rule is that prior runs survive an opt-out, and a preserved run is
        // a prior run — deleting it here would contradict that on the one path
        // where the file happens to be named `androidlog.log`.
        val log = log()
        val sink = sinkWithFailedRotation(log)
        log.addSink(sink)

        log.setRecording(false)
        sink.awaitIdle()
        assertTrue(current().exists())
        assertTrue(sink.readPreviousRun()!!.text.contains("the crash nobody has read yet"))
    }

    // ------------------------------------------------------- the crash marker

    @Test
    fun `a consumed marker does not classify the next run as a crash`() {
        // Truncation is the fallback when the marker cannot be deleted, so an
        // empty marker has to read as consumed — otherwise the fallback would
        // leave the mislabel it exists to prevent.
        current().writeText("an ordinary run\n")
        File(dir, "androidlog.log.crash").writeText("")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertFalse(sink.unacknowledgedCrash)
        assertTrue(previous().single().name.endsWith(".log"))
        assertFalse(previous().single().name.endsWith(".crash.log"))
    }

    @Test
    fun `a marker that cannot be consumed is reported`() {
        // A directory where the marker belongs can be neither deleted (it is not
        // empty) nor truncated. Left unsaid, a marker that survives rotates an
        // ordinary run under the crash suffix at the next start.
        current().writeText("a run\n")
        File(dir, "androidlog.log.crash/wedged").mkdirs()
        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("Crash marker could not be consumed") },
        )
    }

    // --------------------------------------------------- where state is written

    @Test
    fun `the banner state is only ever published on the worker`() {
        // The derived design rests on this: every write to the value and every
        // listener call happens on the one thread that owns the crash record, so
        // no caller can apply a stale answer over a newer one.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        val threads = mutableListOf<String>()
        sink.addCrashListener { threads += Thread.currentThread().name }

        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        sink.acknowledgeCrashBanner()
        sink.awaitIdle()

        // Registration, a raise and a lowering — every one of them on the worker.
        assertEquals(3, threads.size)
        assertTrue(threads.toString(), threads.all { it == "androidlog-file-sink" })
    }

    @Test
    fun `a dismissal that reaches nothing leaves the banner up`() {
        // Starting from a real `true`, because starting from `false` would assert
        // the state the sink begins in and pass however the dismissal behaved.
        // The rename is what a dismissal does, so blocking it with a directory at
        // the destination is a dismissal that reaches nothing.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue("the banner is up to begin with", sink.unacknowledgedCrash)

        val crashFile = previous().single()
        File(dir, crashFile.name.removeSuffix(".crash.log") + ".log").mkdirs()

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertTrue(
            "an unknown check must never be what leaves the banner down",
            sink.unacknowledgedCrash,
        )
        assertTrue("and the crash log is untouched", crashFile.exists())
    }

    // ------------------------------------- results that used to be discarded

    @Test
    fun `an opt-out that cannot purge says so once the log works again`() {
        // A purge that fails is the leak the purge exists to close, so it cannot
        // go unsaid — but recording is off by the time it runs, so a line
        // recorded then is dropped by the gate. It is held for the next write,
        // which happens only once recording is back on.
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("collected before the opt-out")
        sink.awaitIdle()

        // Neither deletable (not empty) nor truncatable, so both mechanisms fail.
        current().delete()
        File(dir, "androidlog.log/wedged").mkdirs()

        log.setRecording(false)
        sink.awaitIdle()
        assertFalse(
            "nothing is recorded while the log is off",
            log.snapshot().any { it.contains("could not remove") },
        )

        log.setRecording(true)
        log.event("after")
        sink.awaitIdle()
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not remove this run's saved log") },
        )
    }

    @Test
    fun `a run that could not be read is named in the report`() {
        // Swallowing it produced a report that looked complete while the run it
        // was sent about was missing, with nothing to tell the reader.
        File(dir, "androidlog-prev-1.log").mkdirs()
        File(dir, "androidlog-prev-2.log").writeText("readable\n")
        val log = log()
        val sink = sink(log)

        val report = sink.readPreviousRun()!!.text
        assertTrue(report, report.contains("could not be read"))
        assertTrue(report, report.contains("readable"))
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be read") },
        )
    }

    @Test
    fun `an unreadable run alone still says so rather than reading as no log`() {
        // The other shape of the same harm: returning null here would say "no
        // earlier run" when the truth is "one, and it could not be read".
        File(dir, "androidlog-prev-1.log").mkdirs()
        val report = sink().readPreviousRun()?.text
        assertNotNull(report)
        assertTrue(report!!, report.contains("could not be read"))
    }

    @Test
    fun `a handle says whether it covers every run still on disk`() {
        // Both directions, because the flag only earns its place if a caller can
        // tell the two apart: the text reads the same either way once the notice
        // is one line among many, and the skipped file is in neither `files` nor
        // the text, so nothing else distinguishes a partial handle from a whole
        // one.
        File(dir, "androidlog-prev-1.log").writeText("readable\n")
        assertTrue("nothing was skipped", sink().readPreviousRun()!!.complete)

        File(dir, "androidlog-prev-2.log").mkdirs()
        val partial = sink().readPreviousRun()!!
        assertTrue(partial.text, partial.text.contains("readable"))
        assertFalse("a run was skipped and is still there", partial.complete)
    }

    @Test
    fun `a handle trimmed by the persisted budget does not call itself complete`() {
        // The trim drops whole lines off the front, so an older run can lose
        // every line of its own to a verbose newer one -- and its file is
        // still in the handle, so a share deletes it. A handle calling itself
        // complete there hands a consumer a report missing that run entirely
        // and then destroys it (Codex, PR #20).
        File(dir, "androidlog-prev-1.log").writeText("the oldest run, first to go\n")
        File(dir, "androidlog-prev-2.log").writeText(
            (1..12_000).joinToString("\n") { "line $it of a very talkative run" } + "\n",
        )
        val handle = sink().readPreviousRun()!!

        assertFalse("the budget cut something", handle.text.contains("the oldest run"))
        assertFalse(handle.complete)
    }

    @Test
    fun `a run the budget dropped whole is not consumed by the share that left it out`() {
        // The skip cases leave their files alone because those files never
        // entered the handle. The trim is the one that would destroy what it
        // left out: the run was read, listed, and would be deleted by a share
        // carrying none of it (Codex, PR #20). So it leaves the handle too, and
        // the next share -- with the talkative run gone -- carries it.
        val older = File(dir, "androidlog-prev-1.log").apply { writeText("the oldest run, first to go\n") }
        val newer = File(dir, "androidlog-prev-2.log").apply {
            writeText((1..12_000).joinToString("\n") { "line $it of a very talkative run" } + "\n")
        }
        val sink = sink()

        val first = sink.readPreviousRun()!!
        assertFalse("the premise: the budget cut the older run out", first.text.contains("the oldest run"))
        sink.clearPreviousRun(first)
        sink.awaitIdle()

        assertTrue("the run nobody was sent survives", older.exists())
        assertFalse("the run that was sent does not", newer.exists())

        // And it is offered on the next share, rather than waiting to be pruned.
        val second = sink.readPreviousRun()!!
        assertTrue(second.text, second.text.contains("the oldest run"))
        assertTrue("with nothing left behind this time", second.complete)
    }

    @Test
    fun `an empty run ahead of an omitted one does not hand it back to be deleted`() {
        // The empty file contributed nothing to the trim, so it must not spend
        // or clear the outstanding drop count. Letting it fall through to the
        // reset marked the wholly-omitted run beside it as consumable, and the
        // share that carried none of it deleted it (Codex, PR #20).
        //
        // Ordered by modification time, oldest first, which is the order the
        // trim drops in -- so the times are set rather than left to chance.
        val empty = File(dir, "androidlog-prev-1.log").apply { writeText("") }
        val omitted = File(dir, "androidlog-prev-2.log").apply { writeText("the run that gets cut\n") }
        val verbose = File(dir, "androidlog-prev-3.log").apply {
            writeText((1..12_000).joinToString("\n") { "line $it of a very talkative run" } + "\n")
        }
        assertTrue(empty.setLastModified(1_000L))
        assertTrue(omitted.setLastModified(2_000L))
        assertTrue(verbose.setLastModified(3_000L))
        val sink = sink()

        val handle = sink.readPreviousRun()!!
        assertFalse("the premise: the budget cut the middle run out", handle.text.contains("the run that gets cut"))
        sink.clearPreviousRun(handle)
        sink.awaitIdle()

        assertTrue("the run nobody was sent survives", omitted.exists())
        assertFalse("the run that was sent does not", verbose.exists())
    }

    @Test
    fun `a handle from a listing that failed says it covers nothing`() {
        // The strongest incomplete case: not one run was accounted for, and
        // every one of them is still on disk.
        val handle = sink(at = unlistable()).readPreviousRun()!!
        assertFalse(handle.complete)
    }

    @Test
    fun `a shared run that cannot be discarded stays tracked and is reported`() {
        // Dropping it from the tracked set while its contents survive re-sends a
        // report the user has already sent, with nothing to say so.
        File(dir, "androidlog-prev-1.log").writeText("shared once\n")
        val log = log()
        val sink = sink(log)
        val shared = sink.readPreviousRun()!!

        // A directory in place of the file: it cannot be deleted (not empty) and
        // cannot be truncated, so the discard fails for real.
        File(dir, "androidlog-prev-1.log").delete()
        File(dir, "androidlog-prev-1.log/wedged").mkdirs()

        sink.clearPreviousRun(shared)
        sink.awaitIdle()
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be discarded") },
        )

        // And it is still tracked, so the next share retries it rather than
        // leaving the contents to ride a later report.
        File(dir, "androidlog-prev-1.log").deleteRecursively()
        File(dir, "androidlog-prev-1.log").writeText("shared once\n")
        sink.clearPreviousRun(shared)
        sink.awaitIdle()
        assertFalse("the retry reached it", File(dir, "androidlog-prev-1.log").exists())
    }

    @Test
    fun `an emptied crash log no longer raises the banner`() {
        // Truncation is one of the two ways contents are discarded, so an empty
        // crash-suffixed file is a log that has already been given up — offering
        // it would raise a banner with nothing behind it.
        File(dir, "androidlog-prev-1.crash.log").writeText("")
        val sink = sink()
        sink.requestCrashRecompute()
        sink.awaitIdle()
        assertFalse(sink.unacknowledgedCrash)
    }

    // -------------------------------------- every failure has somewhere to go

    @Test
    fun `a prune that cannot remove an old run says so`() {
        // A prune that silently did not happen leaves the run in every later
        // report and defeats the retention bound it is there to enforce.
        repeat(7) { index ->
            File(dir, "androidlog-prev-$index.log").writeText("run $index\n")
            File(dir, "androidlog-prev-$index.log").setLastModified(1_000L + index * 1_000L)
        }
        // The oldest is a non-empty directory: neither deletable nor truncatable.
        File(dir, "androidlog-prev-0.log").delete()
        File(dir, "androidlog-prev-0.log/wedged").mkdirs()
        // Oldest by mtime, so it is in the pruned set — creating the directory
        // gave it a fresh one.
        File(dir, "androidlog-prev-0.log").setLastModified(1_000L)
        current().writeText("newest\n")

        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be pruned") },
        )
    }

    @Test
    fun `a listener that throws is reported and does not stop the next one`() {
        // An observer that throws stops updating, and the screen it was driving
        // then shows a banner that has quietly stopped moving.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val log = log()
        val sink = sink(log)
        var reached = false
        sink.addCrashListener { throw IllegalStateException("boom") }
        sink.addCrashListener { reached = true }

        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue("the next listener still ran", reached)
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("A crash-state listener threw") },
        )
    }

    @Test
    fun `a listener that throws on its first value is reported`() {
        // The registration delivery is a delivery like any other. Reported on
        // its own, because a listener that throws on the value it is handed at
        // registration never reaches the change path the test above covers.
        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        sink.addCrashListener { throw IllegalStateException("boom") }
        sink.awaitIdle()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("A crash-state listener threw") },
        )
    }

    @Test
    fun `a removed listener does not leave its spell latched on the next one`() {
        // The spell is about the observer that is failing, so it belongs to that
        // observer. Shared, it outlived the registration that set it — and the
        // next observer's very first failure was swallowed as the tail of
        // somebody else's.
        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val leaving = DebugFileSink.CrashListener { throw IllegalStateException("boom") }
        sink.addCrashListener(leaving)
        sink.awaitIdle()
        sink.removeCrashListener(leaving)
        sink.awaitIdle()

        sink.addCrashListener { throw IllegalStateException("boom") }
        sink.awaitIdle()

        assertEquals(
            log.snapshot().toString(),
            2,
            log.snapshot().count { it.contains("A crash-state listener threw") },
        )
    }

    @Test
    fun `each failing listener is named, not just the first one`() {
        // A shared latch made the second observer's failure the tail of the
        // first's spell, so a screen driving a banner that had quietly stopped
        // moving had nothing anywhere to explain it (Codex, PR #4). Two
        // observers, two spells.
        val log = log()
        val sink = sink(log)
        sink.addCrashListener { throw IllegalStateException("boom") }
        sink.addCrashListener { throw IllegalStateException("boom") }
        sink.awaitIdle()

        assertEquals(
            log.snapshot().toString(),
            2,
            log.snapshot().count { it.contains("A crash-state listener threw") },
        )

        // And each stays quiet on its own repeat, which is what the spell is for.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertEquals(
            log.snapshot().toString(),
            2,
            log.snapshot().count { it.contains("A crash-state listener threw") },
        )
    }

    /** A worker that refuses every task, the way an exhausted one does. */
    private fun refusingWorker(): ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1).apply { shutdown() }

    /**
     * A worker that refuses its first immediate task, then accepts — an
     * exhausted queue that recovers rather than a shut-down one — and runs every
     * *delayed* task inline, on the thread that scheduled it.
     *
     * Inline is what makes the ordering deterministic. A mirror write is
     * scheduled with no delay by the very act of recording a line, so running it
     * on the caller's thread reproduces exactly the interleaving a real worker
     * hits rarely: the write lands *during* the report, before the calling thread
     * has reached its next statement.
     */
    private class RefusesFirstRunsInline(
        private val delegate: ScheduledExecutorService,
    ) : ScheduledExecutorService by delegate {
        private var refused = false

        override fun execute(command: Runnable) {
            if (!refused) {
                refused = true
                throw RejectedExecutionException("queue full")
            }
            delegate.execute(command)
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            command.run()
            return delegate.schedule({}, 0L, unit)
        }
    }

    /** A worker that refuses its first *delayed* task and accepts the rest. */
    private class RefusesFirstSchedule(
        private val delegate: ScheduledExecutorService,
    ) : ScheduledExecutorService by delegate {
        private var refused = false

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (!refused) {
                refused = true
                throw RejectedExecutionException("queue full")
            }
            return delegate.schedule(command, delay, unit)
        }
    }

    @Test
    fun `a mirror write the worker refuses is named by the next one that lands`() {
        // The one enqueue that cannot report where it fails: `log` runs inside
        // the core's fan-out, so a line recorded from it is dropped. Silence left
        // the file stale with nothing to say persistence had stopped, so the
        // failure is held and said by the first write that does get scheduled --
        // which is the write whose file carries the gap (Codex, PR #4).
        val log = log()
        val sink = DebugFileSink(
            log,
            { dir },
            0L,
            {},
            { "1" },
            { RefusesFirstSchedule(ScheduledThreadPoolExecutor(1)) },
        )
        log.addSink(sink)

        log.event("the entry whose write was refused")
        log.event("the entry whose write lands")
        sink.awaitIdle()

        val notice = log.snapshot().single { it.contains("could not be scheduled, so this run") }
        assertTrue(notice, notice.contains("RejectedExecutionException"))
        assertTrue(
            "and the file is being written again",
            current().readText().contains("the entry whose write lands"),
        )
    }

    @Test
    fun `a rotation the worker refuses leaves the previous run where it lies`() {
        // The stand-down that protects `androidlog.log` is set inside the rotation
        // task, so a worker that refuses that task and then recovers used to
        // leave mirroring armed over a previous run nobody had moved aside —
        // and the first write destroyed it (Codex, PR #4). The worker here runs
        // the write inline, which pins the ordering: reporting the refusal
        // records a line, and recording schedules the write, so the latch has to
        // be set before the report rather than after it.
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, {}, { "1" }, { RefusesFirstRunsInline(ScheduledThreadPoolExecutor(1)) })
        current().writeText("the run nobody rotated aside\n")
        log.addSink(sink)

        sink.start(installCrashHandler = false)
        log.event("this run, which must not land on top of it")
        sink.awaitIdle()

        assertTrue(
            current().readText(),
            current().readText().contains("the run nobody rotated aside"),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be scheduled, so this run is not being saved") },
        )
    }

    @Test
    fun `an opt-out purge the worker refuses reaches the log`() {
        // The purge is enqueued, so a worker that refuses it means `androidlog.log`
        // survives the opt-out with its pre-opt-out contents. Nothing here can
        // hold that failure — the flags are worker-only and the worker is what
        // is refusing — so it is left to `DebugLog`, which holds and reports it.
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, {}, { "1" }, { refusingWorker() })
        log.addSink(sink)

        log.setRecording(false)
        log.setRecording(true)
        log.event("the log is working again")

        val notice = log.snapshot().single { it.contains("failed to clear a saved copy") }
        assertTrue(notice, notice.contains("RejectedExecutionException"))
    }

    @Test
    fun `a listener removed while a slower one is still running is not notified`() {
        // The registry iterates a snapshot, so a removal that lands while an
        // earlier listener is still running does not take the later one out of
        // the loop it is already in.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()

        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        // Both listen for the *change* only, so registration does not consume
        // the latch before the publication this is about.
        sink.addCrashListener { raised ->
            if (raised) {
                holding.countDown()
                released.await()
            }
        }
        var notified = false
        val leaving = DebugFileSink.CrashListener { raised -> if (raised) notified = true }
        sink.addCrashListener(leaving)
        sink.awaitIdle()

        sink.start(installCrashHandler = false)
        holding.await()
        sink.removeCrashListener(leaving)
        released.countDown()
        sink.awaitIdle()

        assertFalse("a listener removed mid-publication is not notified", notified)
    }

    @Test
    fun `a listener re-added while the worker is busy is delivered to once`() {
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        sink.addCrashListener {
            holding.countDown()
            released.await()
        }
        holding.await()

        // Two registrations of one instance, both queued behind the held worker.
        // Only the live one is owed the value; the stale task belongs to a
        // registration that is over.
        var delivered = 0
        val readded = DebugFileSink.CrashListener { delivered++ }
        sink.addCrashListener(readded)
        sink.removeCrashListener(readded)
        sink.addCrashListener(readded)
        released.countDown()
        sink.awaitIdle()

        assertEquals("one registration, one first value", 1, delivered)
    }

    @Test
    fun `a listener removed before its first value is not notified`() {
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        // Occupies the worker, so the registration below is still queued when
        // the test removes it — the window a screen that goes away between the
        // two actually hits.
        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        sink.addCrashListener {
            holding.countDown()
            released.await()
        }
        holding.await()

        var notified = false
        val leaving = DebugFileSink.CrashListener { notified = true }
        sink.addCrashListener(leaving)
        sink.removeCrashListener(leaving)
        released.countDown()
        sink.awaitIdle()

        assertFalse("a removed listener is not delivered to", notified)
    }

    @Test(timeout = 30_000)
    fun `clearing a shared run does not wait on the worker`() {
        current().writeText("shared\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        val shared = sink.readPreviousRun()!!

        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        sink.addCrashListener {
            holding.countDown()
            released.await()
        }
        holding.await()

        // Deadlocks if this waits on the worker, which is exactly what it would
        // do on the main thread from a report's completion callback.
        sink.clearPreviousRun(shared)
        released.countDown()
        sink.awaitIdle()

        assertEquals("and the work still happened", 0, previous().size)
    }

    @Test
    fun `a dismissal the filesystem refuses is reported`() {
        // The banner staying up is right, but a control that has no visible
        // effect and no reason is not something to leave unsaid.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        val crashFile = previous().single()
        File(dir, crashFile.name.removeSuffix(".crash.log") + ".log").mkdirs()

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertTrue(sink.unacknowledgedCrash)
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be dismissed") },
        )
    }

    @Test
    fun `a mirror that cannot write says so once, and stops saying it when it can`() {
        // A mirror that quietly stops writing is the whole feature failing with
        // nothing to say so: the log looks kept, and the next launch finds none.
        val log = log()
        val sink = sink(log)
        log.addSink(sink)

        // A directory where the temp file goes: the write throws for real.
        File(dir, "androidlog.log.tmp/wedged").mkdirs()
        log.event("first")
        sink.awaitIdle()
        log.event("second")
        sink.awaitIdle()
        assertEquals(
            "once per spell, not once per entry",
            1,
            log.snapshot().count { it.contains("could not be saved") },
        )

        File(dir, "androidlog.log.tmp").deleteRecursively()
        log.event("third")
        sink.awaitIdle()
        assertTrue(current().readText().contains("third"))

        // The spell ended, so a later failure is news again.
        File(dir, "androidlog.log.tmp/wedged").mkdirs()
        log.event("fourth")
        sink.awaitIdle()
        assertEquals(2, log.snapshot().count { it.contains("could not be saved") })
    }

    // ---------------------------------------- resuming, and the crash marker

    @Test
    fun `resuming after a stand-down saves what is already buffered`() {
        // Clearing the flag only lets future writes through. Everything logged
        // during the stand-down is in the buffer with nothing on disk, so a
        // silent kill before the next entry would lose the whole run.
        val log = log()
        val sink = sinkWithFailedRotation(log)
        log.addSink(sink)
        log.event("logged while nothing could be written")
        sink.awaitIdle()

        sink.clearPreviousRun(sink.readPreviousRun()!!)
        sink.awaitIdle()

        // No further entry: the resume itself has to be what saves it.
        assertTrue(
            current().readText(),
            current().readText().contains("logged while nothing could be written"),
        )
    }

    @Test
    fun `a crash during a stand-down does not mark the run left in place`() {
        // The marker classifies whatever is in `androidlog.log`, and during a
        // stand-down that is the previous run. Marking it would hand the crash
        // suffix to a run that did not crash and offer it as the evidence.
        File(dir, "androidlog-prev-pinned.log").mkdirs()
        current().writeText("an ordinary earlier run\n")
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, {}, { "pinned" })
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        log.event("this run, about to crash")
        sink.flushForCrash()
        sink.awaitIdle()

        assertFalse("no marker beside another run's log", File(dir, "androidlog.log.crash").exists())
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("crashed while an earlier one was still in place") },
        )

        // And the next start still rotates it as the ordinary run it is.
        File(dir, "androidlog-prev-pinned.log").deleteRecursively()
        val next = sink()
        next.start(installCrashHandler = false)
        next.awaitIdle()
        assertFalse("no banner for a run that did not crash", next.unacknowledgedCrash)
        assertTrue(previous().single().name.endsWith(".log"))
        assertFalse(previous().single().name.endsWith(".crash.log"))
    }

    @Test
    fun `a crash marker that cannot be written is reported`() {
        // Without the marker the next start reads this crash as an ordinary
        // kill, raises no banner, and offers nothing to explain the difference.
        File(dir, "androidlog.log.crash/wedged").mkdirs()
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.event("about to crash")

        sink.flushForCrash()
        sink.awaitIdle()
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be marked") },
        )
        // The snapshot still landed: the marker is contained separately.
        assertTrue(current().readText().contains("about to crash"))
    }

    // --------------------------------- the listing, and resuming safely

    @Test
    fun `a listing that failed is named rather than read as no earlier run`() {
        // The per-file read path already says so; the listing itself is one
        // level up and was still collapsing "couldn't look" into "nothing there".
        val log = log()
        val sink = sink(log, at = unlistable())

        val report = sink.readPreviousRun()?.text
        assertNotNull(report)
        assertTrue(report!!, report.contains("could not be listed"))
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be listed") },
        )
    }

    @Test
    fun `mirroring stays down when a shared run's marker cannot be consumed`() {
        // Resuming here would write this run to `androidlog.log` beside a marker that
        // survived, and the next start would read an ordinary run as a crash.
        // The marker is a directory, so it is unconsumable — and therefore also
        // reads as saying nothing, which is why the rotation destination below
        // carries the plain suffix rather than the crash one.
        File(dir, "androidlog.log.crash/wedged").mkdirs()
        File(dir, "androidlog-prev-pinned.log").mkdirs()
        current().writeText("an earlier run\n")
        val log = log()
        val sink = sinkRotatingTo("pinned", log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        log.event("this run, which must not be saved beside a stale marker")
        sink.awaitIdle()
        sink.clearPreviousRun(sink.readPreviousRun()!!)
        sink.awaitIdle()

        assertFalse(
            "the run is not written beside the marker",
            current().exists() &&
                current().readText().contains("this run, which must not be saved"),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("an earlier crash marker could not be cleared") },
        )
    }

    // -------------------------------------------- a marker left at startup

    @Test
    fun `a marker left behind at startup stops this run being saved`() {
        // The rotation succeeds, so nothing is being protected — but a marker
        // that survives sits beside whatever is written next, and the following
        // start would hand the crash suffix to a run that did not crash.
        File(dir, "androidlog.log.crash/wedged").mkdirs()
        current().writeText("an earlier run\n")
        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        log.event("this run, which must not be saved beside a stale marker")
        sink.awaitIdle()
        assertFalse(
            "not written beside the marker",
            current().exists() && current().readText().contains("which must not be saved"),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("crash marker could not be cleared") },
        )
    }

    @Test
    fun `a crash hook that throws is reported and the snapshot still lands`() {
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, { throw IllegalStateException("boom") }, { "n" })
        log.addSink(sink)
        log.event("about to crash")

        sink.flushForCrash()
        sink.awaitIdle()
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("The crash hook failed") },
        )
        assertTrue("the snapshot is not taken down with it", current().readText().contains("about to crash"))
    }

    @Test
    fun `a failed listing forgets what an earlier read surfaced`() {
        // Otherwise a clear following that failed read deletes logs that were not
        // in the report just sent, which cannot be undone.
        val logs = File(dir, "logs").apply { mkdirs() }
        File(logs, "androidlog-prev-1.log").writeText("never sent\n")
        val sink = sink(at = logs)
        assertTrue(sink.readPreviousRun()!!.text.contains("never sent"))

        // Make the directory unlistable without destroying it, then read again.
        val stash = File(dir, "logs.stash")
        logs.renameTo(stash)
        File(dir, "logs").writeText("not a directory")
        val fromFailedRead = sink.readPreviousRun()!!
        assertTrue(fromFailedRead.text.contains("could not be listed"))

        // Restore it and follow the documented flow.
        File(dir, "logs").delete()
        stash.renameTo(logs)
        sink.clearPreviousRun(fromFailedRead)
        sink.awaitIdle()
        assertTrue(
            "a log absent from the last report is not deleted by its clear",
            File(logs, "androidlog-prev-1.log").exists(),
        )
    }

    // ------------------------------ reports that outlive a disabled log

    @Test
    fun `a listener registered late is told the current state`() {
        // `start()` runs in Application.onCreate, so by the time a screen
        // registers the value has usually settled — and publication notifies
        // only on a change, so a recompute deriving the same answer sends
        // nothing. A UI starting at false would miss a banner already raised.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue(sink.unacknowledgedCrash)

        val seen = mutableListOf<Boolean>()
        sink.addCrashListener { seen += it }
        sink.requestCrashRecompute()
        sink.awaitIdle()

        assertEquals("told once, on registration", listOf(true), seen)
    }

    @Test
    fun `an opt-out purge that throws is held and named once the log works again`() {
        // The refused-delete path is held and reported; the exceptional one was
        // swallowed whole, so the file survived the opt-out with no record that
        // anything had gone wrong.
        val log = log()
        var thrown = false
        val sink = DebugFileSink(
            log,
            {
                // Fails on the purge's own first touch of the directory, the way
                // a lazily-resolved cache directory can, then works.
                if (!thrown) {
                    thrown = true
                    throw IllegalStateException("the log directory could not be resolved")
                }
                dir
            },
            0L,
            {},
            { "1" },
        )
        log.addSink(sink)

        log.setRecording(false)
        sink.awaitIdle()
        assertTrue("the purge really did throw", thrown)
        // Nothing can be said while the log is off, which is what the holding is
        // for: it is said on the next write instead.
        log.setRecording(true)
        log.event("the log is working again")
        sink.awaitIdle()

        val said = log.snapshot().single { it.contains("could not remove this run's saved log") }
        assertTrue("and it names what failed", said.contains("IllegalStateException"))
    }

    @Test
    fun `a prior run whose crash record cannot be read leaves the banner up`() {
        // Answering "not crashed" for an entry nobody could read lowers a banner
        // that is already up, and nothing guarantees another recompute before the
        // user's chance to report has passed (Codex, PR #4).
        current().writeText("the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val log = log()
        val sink = sinkRotatingTo("pinned", log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue("the premise: the banner is up", sink.unacknowledgedCrash)

        // The rotated crash log becomes unreadable: a self-referencing symlink,
        // whose attributes fail with a real filesystem error rather than "not
        // found".
        val rotated = File(dir, "androidlog-prev-pinned.crash.log")
        assertTrue("the premise: it was rotated under the crash name", rotated.delete())
        Files.createSymbolicLink(rotated.toPath(), rotated.toPath())

        sink.requestCrashRecompute()
        sink.awaitIdle()

        assertTrue("the banner is left exactly as it was", sink.unacknowledgedCrash)
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not read a prior run") },
        )
    }

    @Test
    fun `an entry nobody can classify is neither counted nor deleted`() {
        // Not a run, so it cannot spend a slot; not known to be anything else, so
        // deleting it is the one irreversible answer to "could not tell".
        repeat(MAX_KEPT) { index ->
            File(dir, "androidlog-prev-$index.log").apply {
                writeText("run $index\n")
                setLastModified(1_000L + index * 1_000L)
            }
        }
        val opaque = File(dir, "androidlog-prev-opaque.log").toPath()
        Files.createSymbolicLink(opaque, opaque)
        current().writeText("this run\n")

        val sink = sinkRotatingTo("new")
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue(
            "the entry is still there, not deleted on a guess",
            Files.isSymbolicLink(opaque),
        )
        assertEquals(
            "and the bound counts runs, so the oldest real one went instead",
            MAX_KEPT,
            previous().count { it.isFile && it.length() > 0L },
        )
    }

    @Test
    fun `a rotation that failed prunes nothing`() {
        // The copy fallback can leave a partial file already wearing a prior-run
        // name, and a partial is a non-empty regular file — the newest run of
        // all. Pruning after a rotation that failed therefore deletes the oldest
        // genuine diagnostic to make room for the wreckage (Codex, PR #4).
        File(dir, "androidlog-prev-pinned.crash.log/held").mkdirs()
        repeat(MAX_KEPT + 1) { index ->
            File(dir, "androidlog-prev-$index.log").apply {
                writeText("run $index\n")
                setLastModified(1_000L + index * 1_000L)
            }
        }
        current().writeText("the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")

        val sink = sinkRotatingTo("pinned")
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue(
            "every genuine run survives a rotation that never happened",
            (0..MAX_KEPT).all { File(dir, "androidlog-prev-$it.log").isFile },
        )
    }

    /** A worker that refuses its [refuseAt]-th immediate task and accepts the rest. */
    private class RefusesNth(
        private val delegate: ScheduledExecutorService,
        private val refuseAt: Int,
    ) : ScheduledExecutorService by delegate {
        private var seen = 0

        override fun execute(command: Runnable) {
            seen++
            if (seen == refuseAt) throw RejectedExecutionException("queue full")
            delegate.execute(command)
        }
    }

    @Test
    fun `a first value the worker refused is delivered by the next recompute`() {
        // Registration promises one delivery, and the task carrying it can be
        // refused. Publication is change-only, so a recompute deriving the same
        // answer sent nothing either — and a screen opened over an
        // already-raised banner missed it until the state happened to change
        // (Codex, PR #4).
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val log = log()
        // The rotation is the first task; the listener's first value is the
        // second, and that is the one refused.
        val sink = DebugFileSink(
            log,
            { dir },
            0L,
            {},
            { "1" },
            { RefusesNth(ScheduledThreadPoolExecutor(1), refuseAt = 2) },
        )
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertTrue("the premise: the banner is up", sink.unacknowledgedCrash)

        var seen: Boolean? = null
        sink.addCrashListener { raised -> seen = raised }
        sink.awaitIdle()
        assertNull("the premise: its first value was refused", seen)
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("first value could not be scheduled") },
        )

        sink.requestCrashRecompute()
        sink.awaitIdle()

        assertEquals("the recompute makes good on the promised value", true, seen)
    }

    @Test
    fun `an obstruction under a prior-run name does not spend a retention slot`() {
        // The directory a failed rotation leaves behind is not a run: it cannot
        // be read into a report and cannot be removed, so counting it spent a
        // permanent slot and deleted a genuine run at every start to make room
        // for something that was never a diagnostic (Codex, PR #4).
        // Four genuine runs, an obstruction newer than all of them, and one more
        // run about to rotate in: five runs, which is the bound exactly. Counting
        // the obstruction makes six and prunes the oldest.
        repeat(MAX_KEPT - 1) { index ->
            File(dir, "androidlog-prev-$index.log").apply {
                writeText("run $index\n")
                setLastModified(1_000L + index * 1_000L)
            }
        }
        File(dir, "androidlog-prev-obstruction.log").mkdirs()
        File(dir, "androidlog-prev-obstruction.log/held").writeText("not a run\n")
        File(dir, "androidlog-prev-obstruction.log").setLastModified(9_000L)
        current().writeText("this run\n")

        val log = log()
        val sink = sinkRotatingTo("new", log)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertTrue(
            "the oldest real run survives the obstruction",
            previous().any { it.isFile && it.readText().contains("run 0") },
        )
        assertTrue(
            "and the obstruction is left where it is, not deleted to make room",
            File(dir, "androidlog-prev-obstruction.log").isDirectory,
        )
    }

    @Test
    fun `an empty run does not spend a retention slot`() {
        // An opt-out landing between the purge and a debounced write already
        // scheduled leaves `androidlog.log` holding nothing. Rotated in and counted,
        // it prunes a real diagnostic to keep a file with no lines in it.
        repeat(MAX_KEPT) { index ->
            File(dir, "androidlog-prev-$index.log").writeText("run $index\n")
            File(dir, "androidlog-prev-$index.log").setLastModified(1_000L + index * 1_000L)
        }
        current().writeText("")

        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertEquals("the placeholder is gone, not a real run", MAX_KEPT, previous().size)
        assertTrue(
            "the oldest real run survives",
            previous().any { it.readText().contains("run 0") },
        )
        assertFalse("and nothing empty was kept", previous().any { it.length() == 0L })
    }

    /**
     * Refuses `submit` once a test opens the gate, while leaving `execute`
     * working — so a read can be turned away *before* it becomes a task, which
     * is the case an accepted-then-abandoned read cannot reach.
     */
    private class RefusesSubmitOnce(
        private val delegate: ScheduledExecutorService,
    ) : ScheduledExecutorService by delegate {
        var arm = false
        private var refused = false

        override fun <T : Any?> submit(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> {
            if (arm && !refused) {
                refused = true
                throw RejectedExecutionException("queue full")
            }
            return delegate.submit(task)
        }
    }

    @Test
    fun `a stand-down over a marker resumes once the obstruction is gone`() {
        // The rotation *succeeded*, so `androidlog.log` is gone and there is no
        // retained run for a share to clear — and the resume in the share only
        // fires for `androidlog.log` itself. Without a re-check this process never
        // persisted another line, however long the obstruction lasted.
        current().writeText("02-01 10:00:00.000 D the run before\n")
        File(dir, "androidlog.log.crash/wedged").mkdirs()

        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertEquals("the run before was rotated aside", 1, previous().size)
        log.event("logged while the marker was stuck")
        sink.awaitIdle()
        assertFalse("nothing is written beside a marker that would mislabel it", current().exists())

        File(dir, "androidlog.log.crash").deleteRecursively()
        log.event("logged once the marker could go")
        sink.awaitIdle()

        assertTrue(
            current().let { if (it.exists()) it.readText() else "<absent>" },
            current().exists() && current().readText().contains("logged once the marker could go"),
        )
        assertTrue(
            "and the run that was accumulating all along is saved with it",
            current().readText().contains("logged while the marker was stuck"),
        )
    }

    @Test
    fun `a crash after a marker stand-down clears is still marked`() {
        // The stand-down recovers inside the write. Left to recover only there,
        // the crash branch above it still sees the old answer, skips the marker,
        // and then the write persists the crashing run unmarked — so the next
        // start reads a real crash as an ordinary kill.
        current().writeText("02-01 10:00:00.000 D the run before\n")
        File(dir, "androidlog.log.crash/wedged").mkdirs()

        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.event("this run, which then crashed")
        sink.awaitIdle()

        File(dir, "androidlog.log.crash").deleteRecursively()
        sink.flushForCrash()

        assertTrue(
            "the crash is marked, so the next start raises a banner for it",
            File(dir, "androidlog.log.crash").isFile,
        )
        assertTrue(
            current().let { if (it.exists()) it.readText() else "<absent>" },
            current().exists() && current().readText().contains("this run, which then crashed"),
        )
    }

    @Test
    fun `a file whose existence cannot be read is discarded, not assumed gone`() {
        // A self-referencing symlink: `File.exists()` follows it, fails, and
        // answers false — the same answer it gives for a file that is not there.
        // Treating that as "already gone" reports a successful opt-out purge over
        // an entry that is still on disk.
        val path = current().toPath()
        Files.createSymbolicLink(path, path)
        assertFalse("the premise: it reads as absent", current().exists())

        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        log.setRecording(false)
        sink.awaitIdle()

        assertFalse(
            "the entry is gone, not merely reported gone",
            Files.exists(path, LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun `a stand-down while recording is off says why once it is back on`() {
        // The flag latches whether or not a log was open to take the reason, so a
        // reason discarded by the gate leaves every snapshot suppressed for the
        // life of the process with nothing anywhere to explain it.
        File(dir, "androidlog-prev-pinned.crash.log").mkdirs()
        current().writeText("02-01 10:00:00.000 D the crash nobody has read yet\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val log = log()
        // Off before the sink is registered, so the opt-out's own purge never
        // reaches it — this is about the rotation, not about `onCleared`.
        log.setRecording(false)
        val sink = sinkRotatingTo("pinned", log)
        log.addSink(sink)

        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertEquals("nothing could be said at the time", emptyList<String>(), log.snapshot())

        log.setRecording(true)
        log.event("the log is working again")
        sink.awaitIdle()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be rotated aside") },
        )
    }

    @Test
    fun `registering the same listener from two threads leaves one registration`() {
        // `CopyOnWriteArrayList` makes each operation safe but not the
        // check-and-add together, so both threads could pass the membership test
        // before either appended — two live registrations, and two of every
        // notification thereafter.
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        repeat(200) {
            var delivered = 0
            val listener = DebugFileSink.CrashListener { delivered++ }
            val barrier = CyclicBarrier(2)
            val racers = List(2) {
                Thread {
                    barrier.await()
                    sink.addCrashListener(listener)
                }.apply { start() }
            }
            racers.forEach { it.join(5_000) }
            sink.awaitIdle()
            assertEquals("one registration, one first value", 1, delivered)
            sink.removeCrashListener(listener)
            sink.awaitIdle()
        }
    }

    @Test
    fun `a crash record that cannot be read defers the rotation instead of guessing`() {
        // The rename is one-way. Filing this run under a classification nobody
        // could read loses a real crash's banner for good, so the rotation waits
        // for a start that can read it.
        current().writeText("02-01 10:00:00.000 D the crash nobody has read yet\n")
        // A self-referencing symlink: reading its attributes fails with a real
        // filesystem error rather than "not found", which is the distinction the
        // fix rests on.
        val marker = File(dir, "androidlog.log.crash").toPath()
        Files.createSymbolicLink(marker, marker)

        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.event("this run, which must not replace it")
        sink.awaitIdle()

        assertEquals("nothing was filed under a classification nobody could read", 0, previous().size)
        assertTrue(
            "the previous run is still there for the next start",
            current().readText().contains("the crash nobody has read yet"),
        )
        assertFalse(
            "and is not overwritten in the meantime",
            current().readText().contains("this run, which must not replace it"),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("crash record could not be read") },
        )
    }

    @Test
    fun `a listener registered beside a queued change is told once`() {
        // The registration is in the list the moment it is made, so a change
        // already queued ahead of its first delivery notifies it — and the
        // delivery then handed it the same value again.
        current().writeText("crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()

        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        sink.addCrashListener {
            holding.countDown()
            released.await()
        }
        holding.await()

        // Queued while the worker is held: the rotation's publication first,
        // then this registration's own first delivery.
        sink.start(installCrashHandler = false)
        val seen = mutableListOf<Boolean>()
        sink.addCrashListener { seen += it }
        released.countDown()
        sink.awaitIdle()

        assertEquals("one change, one notification", listOf(true), seen)
    }

    @Test
    fun `a crash after recording is turned off saves nothing and runs no hook`() {
        // The opt-out already emptied the buffer and purged the file, so the
        // snapshot would write an empty run and the marker would classify one
        // that does not exist. Running the app's hook is the same thing a step
        // further: diagnostic work on the user's behalf after they turned
        // diagnostics off, with every line it logs discarded (Codex, PR #4).
        var hookRan = false
        val log = log()
        val sink = DebugFileSink(log, { dir }, 0L, { hookRan = true }, { "1" })
        sink.start(installCrashHandler = false)
        log.addSink(sink)
        log.event("before the opt-out")
        sink.awaitIdle()
        assertTrue("the premise: it was being saved", current().isFile)

        log.setRecording(false)
        sink.awaitIdle()
        sink.flushForCrash()
        sink.awaitIdle()

        assertFalse("the hook did not run", hookRan)
        assertFalse("no crash was marked", File(dir, "androidlog.log.crash").exists())
        assertEquals("and nothing was written back", "", current().takeIf { it.isFile }?.readText() ?: "")
    }

    @Test
    fun `a log left by an app's own earlier sink is never read or rotated`() {
        // Two consumers' hand-written sinks already write `debug.log` and
        // `debug-prev-*` into the same cache directory, under the old rule that
        // rendered every argument in full. Sharing those names would have made
        // the first launch after a migration surface them into a report —
        // disclosing exactly what the ingestion floor withholds, at the one
        // moment nobody would look for it (Codex, PR #4).
        File(dir, "debug.log").writeText("written by the app's own older sink\n")
        File(dir, "debug-prev-1.log").writeText("and an older run of it\n")

        val log = log()
        val sink = sink(log)
        sink.start(installCrashHandler = false)
        log.addSink(sink)
        log.event("this run")
        sink.awaitIdle()

        assertNull("nothing of the app's own is offered as a prior run", sink.readPreviousRun())
        assertEquals(
            "and the app's file is left exactly as it was",
            "written by the app's own older sink\n",
            File(dir, "debug.log").readText(),
        )
        assertEquals(
            "as is its own rotated one",
            "and an older run of it\n",
            File(dir, "debug-prev-1.log").readText(),
        )
        assertTrue("this library writes under its own name", current().isFile)
    }

    @Test
    fun `a clock that went backward does not prune the run that just ended`() {
        // The prune orders by modification time, and a wall clock moved back
        // between runs leaves the newly rotated file older than every run before
        // it — so the oldest-first candidate is the newest diagnostic there is
        // (Codex, PR #4). Under the crash suffix the marker is consumed in the
        // same rotation, so the banner recompute would then find no crash at all.
        repeat(MAX_KEPT) { index ->
            File(dir, "androidlog-prev-old$index.log").apply {
                writeText("older run $index\n")
                setLastModified(9_000_000L + index * 1_000L)
            }
        }
        current().writeText("the run that just crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        // The backward clock, applied before the rotation: a rename carries the
        // modification time across, so the file this launch creates lands older
        // than all five already there.
        current().setLastModified(1_000L)
        val rotated = File(dir, "androidlog-prev-new.crash.log")

        val sink = sinkRotatingTo("new")
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        assertEquals(
            "the premise: it really is the oldest by the time the prune reads it",
            1_000L,
            rotated.lastModified(),
        )

        assertTrue("the run that just ended survived the prune", rotated.isFile)
        assertEquals(
            "with its contents",
            "the run that just crashed\n",
            rotated.readText(),
        )
        assertTrue("so its crash is still reported", sink.unacknowledgedCrash)
        assertEquals("and the bound still held", MAX_KEPT, previous().size)
    }

    @Test
    fun `a caller that never received a handle has nothing to clear`() {
        // The ticket bookkeeping this replaced existed to decide whether one
        // shared slot still meant anything. A handle answers it by construction:
        // a caller whose read was refused holds nothing, so there is nothing it
        // could pass to a clear (Codex, PR #4).
        File(dir, "androidlog-prev-1.log").writeText("never sent\n")
        val worker = RefusesSubmitOnce(ScheduledThreadPoolExecutor(1))
        val sink = DebugFileSink(log(), { dir }, 0L, {}, { "1" }, { worker })
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        worker.arm = true
        assertNull("the read is refused outright", sink.readPreviousRun())
        sink.awaitIdle()

        assertEquals(
            "so the run is untouched",
            "never sent\n",
            File(dir, "androidlog-prev-1.log").readText(),
        )
    }

    @Test
    fun `overlapping reports each clear only what they were given`() {
        // The failure this closes: the files used to live in one slot, so the
        // earlier report's clear consumed whatever the *later* read had
        // surfaced — destroying a run that report never contained, and whose own
        // report might still fail (Codex, PR #4).
        File(dir, "androidlog-prev-1.log").writeText("in both reports\n")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val first = sink.readPreviousRun()!!
        assertTrue("the premise", first.text.contains("in both reports"))

        // A run that only the second read can see.
        File(dir, "androidlog-prev-2.log").writeText("only in the second\n")
        val second = sink.readPreviousRun()!!
        assertTrue("the premise", second.text.contains("only in the second"))

        sink.clearPreviousRun(first)
        sink.awaitIdle()

        assertFalse(
            "the first report's own run is consumed",
            File(dir, "androidlog-prev-1.log").exists(),
        )
        assertEquals(
            "and the one it never contained survives for the report that did",
            "only in the second\n",
            File(dir, "androidlog-prev-2.log").readText(),
        )
    }

    @Test
    fun `a dismissal follows its rename into a report already in flight`() {
        // The clear deletes the paths its handle names, so a dismissal renaming
        // the crash suffix off mid-report has to reach into that handle — or the
        // delete misses and a log the user already sent rides the next one too.
        current().writeText("crashed, and being shared\n")
        File(dir, "androidlog.log.crash").writeText("1")
        val sink = sink()
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        val inFlight = sink.readPreviousRun()!!
        assertTrue("the premise", inFlight.text.contains("crashed, and being shared"))
        assertTrue("under the crash name", previous().single().name.endsWith(".crash.log"))

        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertTrue("renamed out from under it", previous().single().name.endsWith(".log"))

        sink.clearPreviousRun(inFlight)
        sink.awaitIdle()

        assertEquals("the clear still reached it", 0, previous().size)
    }

    @Test
    fun `a rotation onto a name already taken keeps the run that is there`() {
        // `renameTo` does not refuse a taken destination: POSIX rename replaces
        // an existing regular file and answers true. The names collide for real,
        // because `System.nanoTime()` measures uptime and its origin resets at
        // boot (Codex, PR #4).
        val taken = File(dir, "androidlog-prev-1.log")
        taken.writeText("an earlier run, never shared\n")
        current().writeText("this run\n")

        val sink = sinkRotatingTo("1")
        sink.start(installCrashHandler = false)
        sink.awaitIdle()

        assertEquals(
            "the run already under that name is untouched",
            "an earlier run, never shared\n",
            taken.readText(),
        )
        assertEquals(
            "and this one is kept where it lies, for the next start to retry",
            "this run\n",
            current().readText(),
        )
    }

    @Test
    fun `a crash flush that cannot complete in time says so`() {
        // The wait is bounded so the handler chain is never held up, but a flush
        // that did not finish means the marker or the snapshot may be missing.
        val log = log()
        val sink = sink(log)
        val holding = CountDownLatch(1)
        val released = CountDownLatch(1)
        sink.addCrashListener {
            holding.countDown()
            released.await()
        }
        holding.await()

        // The worker is provably occupied, so the flush waits out its budget
        // rather than the test waiting out a guess.
        sink.flushForCrash()
        released.countDown()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("The crash flush did not complete") },
        )
    }

    @Test
    fun `a startup rotation that throws stands down rather than overwriting`() {
        // The `moveAside(false)` answer is checked, but an *exception* on the
        // way there left the flag false — and the previous run sitting in
        // `androidlog.log`, for the first mirror write to replace. Thrown from the
        // destination-name seam, which is inside the rotation and past every
        // step that answers rather than throws.
        current().writeText("02-01 10:00:00.000 D the run before\n")
        val log = log()
        val sink = DebugFileSink(
            log,
            { dir },
            0L,
            {},
            { throw IllegalStateException("the destination could not be named") },
        )

        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.addSink(sink)
        log.event("this run, which must not replace the last")
        sink.awaitIdle()

        assertEquals("nothing was rotated", 0, previous().size)
        assertTrue(
            current().readText(),
            current().readText().contains("the run before"),
        )
        assertFalse(
            "and this run did not overwrite it",
            current().readText().contains("this run, which must not replace the last"),
        )
        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any { it.contains("could not be rotated, so this run is not being saved") },
        )
    }

    @Test
    fun `a previous run whose presence cannot be read defers the rotation`() {
        // `File.exists()` answers false for a stat that failed as well as for a
        // file that is not there, and false there means "nothing to rotate" —
        // so a failed lookup enabled mirroring over a run still sitting in
        // `androidlog.log`, which the next snapshot destroys.
        val path = current().toPath()
        Files.createSymbolicLink(path, path)
        assertFalse("the premise: it reads as absent", current().exists())

        val log = log()
        val sink = sink(log)
        log.addSink(sink)
        sink.start(installCrashHandler = false)
        sink.awaitIdle()
        log.event("this run, which must not be saved over it")
        sink.awaitIdle()
        // A second write, because the stand-down is re-checked on every one of
        // them — and that re-check asks the same question about the same file.
        log.event("nor this one")
        sink.awaitIdle()

        assertTrue(
            log.snapshot().toString(),
            log.snapshot().any {
                it.contains("Whether there is a previous run could not be read")
            },
        )
        assertTrue(
            "the entry is untouched, not replaced by this run's snapshot",
            Files.isSymbolicLink(path),
        )
    }

    @Test
    fun `a spell does not latch on a line the disabled log discarded`() {
        // Recording can be off while these paths run, and a line recorded then
        // is dropped by the gate. Latching the guard on it would spend the spell
        // on a report nobody received, and the failure would repeat in silence.
        val log = log()
        val sink = sink(log, at = unlistable())

        log.setRecording(false)
        sink.requestCrashRecompute()
        sink.awaitIdle()

        log.setRecording(true)
        sink.requestCrashRecompute()
        sink.awaitIdle()
        assertEquals(
            "the first attempt spent nothing, so this one reports",
            1,
            log.snapshot().count { it.contains("could not list the log directory") },
        )
    }
}
