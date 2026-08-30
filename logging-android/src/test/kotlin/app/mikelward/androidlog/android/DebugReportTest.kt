package app.mikelward.androidlog.android

import app.mikelward.androidlog.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The share mechanism's own rules, on a plain JVM. The clipboard and chooser
 * calls need a `Context` and are not reachable here; what is covered is the part
 * that was worth sharing between the apps — when a prior run may be consumed,
 * and what the caller is told.
 */
class DebugReportTest {

    private val dir: File = Files.createTempDirectory("androidlog-report").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun log() = DebugLog(readMillis = { 0L })

    private fun sink(log: DebugLog = log()) = DebugFileSink(log, dir)

    /** A sink holding one prior run, so a report has something to consume. */
    private fun sinkWithAPriorRun(log: DebugLog = log()): DebugFileSink {
        File(dir, "androidlog.log").writeText("01-01 00:00:00.000 D an earlier run\n")
        return sink(log).also {
            it.start()
            it.awaitIdle()
        }
    }

    // ------------------------------------------------------- consuming a run

    @Test
    fun `a run is consumed only once the report carrying it is retained`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        val cleared = mutableListOf<PreviousRun>()

        val outcome = DebugReport.settle(report, copied = true, launched = true) { cleared += it }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertEquals(1, cleared.size)
    }

    @Test
    fun `a run survives a report that reached nobody`() {
        // The clipboard is the retained route, so a failed copy means the log
        // stays for the next attempt rather than being spent on a report the
        // user cannot get at.
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        val cleared = mutableListOf<PreviousRun>()

        val outcome = DebugReport.settle(report, copied = false, launched = true) { cleared += it }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertTrue(cleared.toString(), cleared.isEmpty())
    }

    @Test
    fun `a chooser that never opened does not consume the run on its own`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        val cleared = mutableListOf<PreviousRun>()

        val outcome = DebugReport.settle(report, copied = false, launched = false) { cleared += it }

        assertEquals(ShareOutcome.FAILED, outcome)
        assertTrue(cleared.toString(), cleared.isEmpty())
    }

    @Test
    fun `a clipboard copy with no chooser is reported so the user is not left guessing`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        val cleared = mutableListOf<PreviousRun>()

        val outcome = DebugReport.settle(report, copied = true, launched = false) { cleared += it }

        // Consumed, because the clipboard is a delivery the user can still get
        // at -- which is exactly why they have to be told it happened.
        assertEquals(ShareOutcome.COPIED_ONLY, outcome)
        assertEquals(1, cleared.size)
    }

    // -------------------------------------------------- a failed collection

    @Test
    fun `a failed app section still carries and consumes the prior run`() {
        // The app's own state is what failed to render; the prior run was read
        // and is appended regardless, so it really is delivered and consuming
        // it is honest. The failure must not cost the diagnostic the report
        // exists to carry.
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { error("no payload for you") }
        val cleared = mutableListOf<PreviousRun>()

        val outcome = DebugReport.settle(report, copied = true, launched = true) { cleared += it }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertTrue(report.text, "an earlier run" in report.text)
        assertEquals(1, cleared.size)
    }

    @Test
    fun `a payload builder that ignores the prior run still delivers it`() {
        // The library appends it rather than trusting the builder to, so
        // carrying it and consuming it cannot come apart: an app reporting only
        // current state used to have the run consumed for a report that never
        // contained it (Codex, PR #8).
        val sink = sinkWithAPriorRun()

        val report = DebugReport.collect(log(), sink) { "current state only" }

        assertTrue(report.text, "current state only" in report.text)
        assertTrue(report.text, "an earlier run" in report.text)
    }

    @Test
    fun `a failed build still hands back something shareable, naming the type only`() {
        val log = log()
        val report = DebugReport.collect(log, sink(log)) { error("a message nobody may see") }

        assertTrue(report.text, "IllegalStateException" in report.text)
        assertFalse(report.text, "a message nobody may see" in report.text)
        assertTrue(log.snapshot().toString(), log.snapshot().any { "could not be built" in it })
    }

    // ------------------------------------------------------ nothing to share

    @Test
    fun `an app with no file sink still builds a report`() {
        val report = DebugReport.collect(log(), sink = null) { "report with no prior run" }

        assertEquals("report with no prior run", report.text)
    }

    @Test
    fun `a report built without a prior run has nothing to consume`() {
        val report = DebugReport.collect(log(), sink()) { "report" }
        val cleared = mutableListOf<PreviousRun>()

        DebugReport.settle(report, copied = true, launched = true) { cleared += it }

        assertTrue(cleared.toString(), cleared.isEmpty())
    }

    @Test
    fun `the prior run is handed to the payload builder`() {
        val sink = sinkWithAPriorRun()

        val report = DebugReport.collect(log(), sink) { "the app section" }

        assertTrue(report.text, "an earlier run" in report.text)
        assertTrue(report.text, "the app section" in report.text)
    }

    @Test
    fun `a report carries the sink it was collected from`() {
        // Delivering against a sink passed separately let a caller collect from
        // one and clear against another -- or against null, leaving a delivered
        // run to be appended to the next report as well (Codex, PR #8).
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }

        assertSame(sink, report.sink)
    }

    @Test
    fun `an app section comes before the prior run it is appended to`() {
        val sink = sinkWithAPriorRun()

        val report = DebugReport.collect(log(), sink) { "the app section" }

        assertTrue(
            report.text,
            report.text.indexOf("the app section") < report.text.indexOf("an earlier run"),
        )
    }
}
