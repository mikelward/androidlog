package com.mikelward.androidlog.android

import com.mikelward.androidlog.DebugLog
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

    // ------------------------------------------------------ the report's size

    /** [chars] characters of numbered lines, oldest first, so a test can tell which end survived. */
    private fun lines(chars: Int, name: String = "line"): String {
        val out = StringBuilder()
        var n = 1
        while (out.length < chars) out.append("$name ${n++}\n")
        return out.substring(0, chars)
    }

    /** Two prior runs, oldest first: a short one, then one too long for the report's share. */
    private fun sinkWithAShortRunBehindALongOne(): Triple<DebugFileSink, File, File> {
        val older = File(dir, "androidlog-prev-1.log").apply { writeText("the oldest run\n") }
        val newer = File(dir, "androidlog-prev-2.log").apply {
            writeText(lines(30_000, "talkative") + "\nthe stack trace\n")
        }
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))
        return Triple(sink(), older, newer)
    }

    @Test
    fun `a report is never larger than the cap, however much there is to say`() {
        val (sink, _, _) = sinkWithAShortRunBehindALongOne()

        val report = DebugReport.collect(log(), sink) { lines(100_000) }

        assertTrue("${report.text.length}", report.text.length <= DebugReport.MAX_REPORT_CHARS)
        val section = report.text.substring(report.text.indexOf("--- earlier runs ---"))
        assertTrue("${section.length}", section.length + 2 <= DebugReport.MAX_EARLIER_RUNS_CHARS)
    }

    @Test
    fun `an app section within its share arrives whole beside a full prior run`() {
        val (sink, _, _) = sinkWithAShortRunBehindALongOne()
        val own = lines(DebugReport.MAX_REPORT_CHARS - DebugReport.MAX_EARLIER_RUNS_CHARS)

        val report = DebugReport.collect(log(), sink) { own }

        assertTrue(report.text.startsWith("$own\n\n--- earlier runs ---\n"))
        assertFalse(report.text, "cut here" in report.text)
        assertTrue("${report.text.length}", report.text.length <= DebugReport.MAX_REPORT_CHARS)
    }

    @Test
    fun `with no earlier runs the app section can use the whole report`() {
        val own = lines(DebugReport.MAX_REPORT_CHARS)

        assertEquals(own, DebugReport.collect(log(), sink()) { own }.text)
        assertEquals(own, DebugReport.collect(log(), sink = null) { own }.text)
    }

    @Test
    fun `an app section over the cap keeps both ends, on whole lines, and says how much went`() {
        val own = lines(100_000)
        val report = DebugReport.collect(log(), sink = null) { own }

        assertTrue("${report.text.length}", report.text.length <= DebugReport.MAX_REPORT_CHARS)
        val notice = Regex("""\n\[(\d+) characters cut here to keep the report shareable]\n""")
        val match = notice.find(report.text)!!
        val head = report.text.substring(0, match.range.first)
        val tail = report.text.substring(match.range.last + 1)
        // Every character is either kept or counted as cut.
        assertEquals(own.length, head.length + match.groupValues[1].toInt() + tail.length)
        assertTrue(own.startsWith(head) && own.endsWith(tail))
        assertTrue(head, head.startsWith("line 1\n"))
        // Both cuts fall on line breaks, so no line is left half-read.
        assertEquals('\n', own[head.length])
        assertEquals('\n', own[own.length - tail.length - 1])
    }

    @Test
    fun `the earlier runs keep their newest lines`() {
        val (sink, _, _) = sinkWithAShortRunBehindALongOne()

        val report = DebugReport.collect(log(), sink) { "the app section" }

        assertTrue(report.text, report.text.trimEnd().endsWith("the stack trace"))
        assertFalse(report.text, "talkative 1\n" in report.text)
    }

    @Test
    fun `a run the report's share left out is not consumed by it`() {
        val (sink, older, newer) = sinkWithAShortRunBehindALongOne()
        // The premise: the persisted budget alone would have carried it, so it
        // is the report's share that leaves it out.
        assertTrue(sink.readPreviousRun()!!.text.contains("the oldest run"))

        val report = DebugReport.collect(log(), sink) { "the app section" }
        assertFalse(report.text, "the oldest run" in report.text)
        DebugReport.settle(report, copied = true, launched = true) { sink.clearPreviousRun(it) }
        sink.awaitIdle()

        assertTrue("the run nobody was sent survives", older.exists())
        assertFalse("the run that was sent does not", newer.exists())
    }

    @Test
    fun `a heading with no room for the runs leaves them for a later report`() {
        val sink = sinkWithAPriorRun()
        val log = log()

        val report = DebugReport.collect(log, sink, heading = "=".repeat(DebugReport.MAX_EARLIER_RUNS_CHARS)) {
            "the app section"
        }
        val cleared = mutableListOf<PreviousRun>()
        DebugReport.settle(report, copied = true, launched = true) { cleared += it }

        assertEquals("the app section", report.text)
        assertTrue(cleared.toString(), cleared.isEmpty())
        assertTrue(log.snapshot().toString(), log.snapshot().any { "leaves no room for the runs" in it })
        // And an ordinary heading still carries them.
        assertTrue(DebugReport.collect(log(), sink) { "again" }.text.contains("an earlier run"))
    }

    @Test
    fun `a section that fits is returned as it is`() {
        assertEquals("a\nb", DebugReport.keepingEnds("a\nb", 3))
    }

    @Test
    fun `one line too long for the budget keeps both its ends and uses the room`() {
        val kept = DebugReport.keepingEnds("a".repeat(500) + "b".repeat(500), 100)

        assertTrue("${kept.length}", kept.length <= 100)
        // Only the notice's digits can leave room unused: it is sized for the
        // largest count it could name.
        assertTrue("${kept.length}", kept.length >= 99)
        assertTrue(kept, kept.startsWith("aaa") && kept.endsWith("bbb"))
        assertTrue(kept, "characters cut here" in kept)
    }

    // -------------------------------------------------- attaching a screenshot

    @Test
    fun `a screenshot shares as an image the target is granted to read`() {
        // The two facts that must move together: without the grant the chosen
        // app receives a content:// URI it cannot open, and without the image
        // type the chooser never surfaces the image-capable targets.
        val content = DebugReport.shareContentFor(hasScreenshot = true)

        assertEquals("image/png", content.mimeType)
        assertTrue("a screenshot needs a read grant", content.grantRead)
    }

    @Test
    fun `a text-only report shares as plain text with no grant`() {
        // The other direction, asserted so a change that always attached the
        // grant -- or always claimed an image -- cannot pass: a report with no
        // screenshot must stay text/plain and hand out no URI permission.
        val content = DebugReport.shareContentFor(hasScreenshot = false)

        assertEquals("text/plain", content.mimeType)
        assertFalse("a text-only report grants nothing", content.grantRead)
    }
}
