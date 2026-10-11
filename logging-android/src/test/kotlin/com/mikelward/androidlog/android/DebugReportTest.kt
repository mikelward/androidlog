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

    // ---------------------------------------------- what a share changes

    @Test
    fun `a landed copy lowers the crash banner for the runs it carried`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        var acknowledged = 0

        val outcome = DebugReport.settle(report, copied = true, launched = true) { acknowledged++ }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertEquals(1, acknowledged)
    }

    @Test
    fun `a report that reached nobody leaves the banner up`() {
        // The clipboard is the retained route, so a failed copy means the
        // crash still has not reached anywhere the user can get at it.
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        var acknowledged = 0

        val outcome = DebugReport.settle(report, copied = false, launched = true) { acknowledged++ }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertEquals(0, acknowledged)
    }

    @Test
    fun `a chooser that never opened does not lower the banner on its own`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        var acknowledged = 0

        val outcome = DebugReport.settle(report, copied = false, launched = false) { acknowledged++ }

        assertEquals(ShareOutcome.FAILED, outcome)
        assertEquals(0, acknowledged)
    }

    @Test
    fun `a clipboard copy with no chooser is reported so the user is not left guessing`() {
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }
        var acknowledged = 0

        val outcome = DebugReport.settle(report, copied = true, launched = false) { acknowledged++ }

        // The banner comes down, because the clipboard is a copy the user can
        // still get at -- which is exactly why they have to be told it happened.
        assertEquals(ShareOutcome.COPIED_ONLY, outcome)
        assertEquals(1, acknowledged)
    }

    @Test
    fun `sharing deletes nothing, so the runs are still there for the next report`() {
        // Whether a report was sent is unknowable here: the chooser reports
        // nothing back. So the runs stay on disk under their own retention.
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { "report" }

        DebugReport.settle(report, copied = true, launched = true) { sink.acknowledgeCrashesAfterShare() }
        sink.awaitIdle()

        assertTrue(report.text, "an earlier run" in report.text)
        assertTrue(DebugReport.collect(log(), sink) { "again" }.text.contains("an earlier run"))
    }

    // -------------------------------------------------- a failed collection

    @Test
    fun `a failed app section still carries the prior run`() {
        // The app's own state is what failed to render; the prior run was read
        // and is appended regardless, so it really is delivered and lowering
        // the banner for it is honest. The failure must not cost the
        // diagnostic the report exists to carry.
        val sink = sinkWithAPriorRun()
        val report = DebugReport.collect(log(), sink) { error("no payload for you") }
        var acknowledged = 0

        val outcome = DebugReport.settle(report, copied = true, launched = true) { acknowledged++ }

        assertEquals(ShareOutcome.SHARED, outcome)
        assertTrue(report.text, "an earlier run" in report.text)
        assertEquals(1, acknowledged)
    }

    @Test
    fun `a payload builder that ignores the prior run still delivers it`() {
        // The library appends it rather than trusting the builder to, so
        // carrying it and lowering the banner for it cannot come apart (Codex,
        // PR #8).
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
    fun `a report built without a prior run leaves the banner alone`() {
        val report = DebugReport.collect(log(), sink()) { "report" }
        var acknowledged = 0

        DebugReport.settle(report, copied = true, launched = true) { acknowledged++ }

        assertEquals(0, acknowledged)
    }

    @Test
    fun `a report that could not list the earlier runs leaves the banner up`() {
        // A directory that could not be listed may list on the next share, so
        // a report carrying only that notice is not the user's answer to a
        // crash it never reached (Codex, PR #58).
        val unlistable = File(dir, "not-a-directory").apply { writeText("x") }
        val report = DebugReport.collect(log(), DebugFileSink(log(), unlistable)) { "report" }
        assertTrue("the premise: it says so", "could not be listed" in report.text)
        var acknowledged = 0

        DebugReport.settle(report, copied = true, launched = true) { acknowledged++ }

        assertEquals(0, acknowledged)
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
    fun `a share keeps every run on disk, shown or not`() {
        val (sink, older, newer) = sinkWithAShortRunBehindALongOne()
        // The premise: the persisted budget alone would have carried it, so it
        // is the report's share that leaves it out.
        assertTrue(sink.readPreviousRun()!!.text.contains("the oldest run"))

        val report = DebugReport.collect(log(), sink) { "the app section" }
        assertFalse(report.text, "the oldest run" in report.text)
        DebugReport.settle(report, copied = true, launched = true) { sink.acknowledgeCrashesAfterShare() }
        sink.awaitIdle()

        assertTrue("the run nobody was sent survives", older.exists())
        assertTrue("so does the run that was sent", newer.exists())
    }

    @Test
    fun `a heading with no room for the runs leaves them for a later report`() {
        val sink = sinkWithAPriorRun()
        val log = log()

        val report = DebugReport.collect(log, sink, heading = "=".repeat(DebugReport.MAX_EARLIER_RUNS_CHARS)) {
            "the app section"
        }
        var acknowledged = 0
        DebugReport.settle(report, copied = true, launched = true) { acknowledged++ }

        assertEquals("the app section", report.text)
        assertEquals(0, acknowledged)
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

    // -------------------------------------------------------- report size

    @Test
    fun `a report fits what a paste keeps whole`() {
        // Pasted reports at 60,000 were kept to about their first 20,000
        // characters; the cap has to sit below that, with room to spare.
        assertTrue(DebugReport.MAX_REPORT_CHARS <= 18_000)
        assertEquals(
            DebugReport.MAX_REPORT_CHARS,
            DebugReport.APP_SECTION_CHARS + DebugReport.MAX_EARLIER_RUNS_CHARS,
        )
        // Room for a whole process-exit batch and more besides.
        assertTrue(DebugReport.APP_SECTION_CHARS > ProcessExits.maxBatchChars())
    }

    // ------------------------------------------------------------ log lines

    @Test
    fun `the log keeps how the run began when a busy run evicted it`() {
        val log = log()
        log.pinnedEvent("processExit %s", "crash")
        repeat(DebugLog.DEFAULT_MAX_ENTRIES * 2) { log.event("busy %s", it) }
        // The premise: the ring alone no longer has it.
        assertFalse(log.snapshot().any { "processExit" in it })

        val lines = DebugReport.logLines(log, budgetChars = 4_000)

        val exit = lines.indexOfFirst { "processExit" in it }
        assertTrue(lines.toString(), exit >= 0 && exit < lines.indexOfFirst { "busy" in it })
        assertTrue(lines.last(), lines.last().endsWith("busy ${DebugLog.DEFAULT_MAX_ENTRIES * 2 - 1}"))
        assertTrue(lines.sumOf { it.length + 1 } <= 4_000)
    }

    @Test
    fun `a run with nothing pinned gives the whole budget to its newest lines`() {
        val log = log()
        repeat(DebugLog.DEFAULT_MAX_ENTRIES) { log.event("busy %s", it) }

        val lines = DebugReport.logLines(log, budgetChars = 2_000)

        assertEquals(log.boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = 2_000), lines)
        assertTrue(lines.sumOf { it.length + 1 } > 1_900)
    }

    @Test
    fun `a budget with no room keeps nothing rather than failing`() {
        val log = log()
        log.pinnedEvent("processExit %s", "crash")
        log.event("busy")

        assertEquals(emptyList<String>(), DebugReport.logLines(log, budgetChars = -1))
    }

    @Test
    fun `start-up lines still among the newest leave their reserve to the log`() {
        // A fresh run: its start-up lines are pinned and still its newest, so
        // the tail carries them and the reserve held for them goes unneeded.
        val log = log()
        repeat(100) { log.event("busy %s %s", it, "x".repeat(100)) }
        repeat(5) { log.pinnedEvent("started %s %s", it, "y".repeat(1_000)) }

        val lines = DebugReport.logLines(log, budgetChars = 10_000)
        val used = lines.sumOf { it.length + 1 }

        assertEquals(5, lines.count { "started " in it })
        // Short of the budget by less than one line, not by the reserve.
        assertTrue("$used", used in 9_800..10_000)
    }

    @Test
    fun `pinned lines spread through the run still leave the log full`() {
        // Each larger tail takes in one more group of pinned lines, so the
        // split settles only after a dozen passes; none of the room is lost.
        val log = log()
        repeat(8) { group ->
            repeat(4) { log.pinnedEvent("started %s-%s %s", group, it, "y".repeat(265)) }
            repeat(8) { log.event("busy %s-%s", group, it) }
        }

        val lines = DebugReport.logLines(log, budgetChars = 12_000)
        val used = lines.sumOf { it.length + 1 }

        assertTrue("$used", used in 11_400..12_000)
        assertTrue(lines.last(), lines.last().endsWith("busy 7-7"))
    }

    @Test
    fun `a larger exit batch keeps its oldest exits when its reserve is passed`() {
        val log = log()
        val records = ProcessExits.DEFAULT_MAX_RECORDS * 4
        repeat(records) { log.pinnedEvent("processExit %s %s", it, "z".repeat(400)) }
        repeat(DebugLog.DEFAULT_MAX_ENTRIES) { log.event("busy %s", it) }
        // The premise: the default reserve is too small for this many.
        assertTrue(records * 430 > ProcessExits.maxBatchChars())

        val lines = DebugReport.logLines(
            log,
            budgetChars = 14_000,
            pinnedReserveChars = ProcessExits.maxBatchChars(records),
        )

        assertTrue(lines.any { "processExit 0 " in it })
        assertEquals(records, lines.count { "processExit " in it })
    }

    @Test
    fun `a share lowers the banner for a crash too old for the report, and keeps its log`() {
        // The share is the user acting on the banner; holding it up for a
        // crash no report has room for leaves Dismiss the only way down
        // (maintainer, 2026-10-11).
        val older = File(dir, "androidlog-prev-1.crash.log").apply { writeText("the old crash\n") }
        val newer = File(dir, "androidlog-prev-2.log").apply {
            writeText(lines(30_000, "talkative") + "\nthe newest line\n")
        }
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))
        val sink = sink()

        val report = DebugReport.collect(log(), sink) { "the app section" }
        assertFalse("the premise: the report has no room for it", "the old crash" in report.text)
        DebugReport.settle(report, copied = true, launched = true) { sink.acknowledgeCrashesAfterShare() }
        sink.awaitIdle()

        assertFalse("its banner is down", sink.unacknowledgedCrash)
        assertTrue(
            "and its log is kept, still shareable",
            File(dir, "androidlog-prev-1.log").readText().contains("the old crash"),
        )
    }

    @Test
    fun `a share that cannot lower the banner is not reported as a failed dismissal`() {
        // A crash whose rename is blocked keeps the banner up whatever acts on
        // it. After a dismissal that is the dismissal's failure to report; after
        // a share it is not, or the app shows a dismissal error for a share
        // that worked (Codex, PR #58).
        File(dir, "androidlog-prev-1.crash.log").writeText("the crash\n")
        File(dir, "androidlog-prev-1.log/held").mkdirs()
        val sink = sink()
        sink.requestCrashRecompute()
        sink.awaitIdle()
        assertTrue("the premise: the banner is up", sink.unacknowledgedCrash)

        val report = DebugReport.collect(log(), sink) { "the app section" }
        DebugReport.settle(report, copied = true, launched = true) { sink.acknowledgeCrashesAfterShare() }
        sink.awaitIdle()

        assertTrue("the premise: the share could not lower it", sink.unacknowledgedCrash)
        assertFalse(sink.storageOutcomes.crashDismissalFailed)
        sink.acknowledgeCrashBanner()
        sink.awaitIdle()
        assertTrue("while a dismissal that could not is reported", sink.storageOutcomes.crashDismissalFailed)
    }

    @Test
    fun `a share lowers the banner for a crash the report carried`() {
        val crashed = File(dir, "androidlog-prev-1.crash.log").apply { writeText("the crash\n") }
        val sink = sink()

        val report = DebugReport.collect(log(), sink) { "the app section" }
        assertTrue(report.text, "the crash" in report.text)
        DebugReport.settle(report, copied = true, launched = true) { sink.acknowledgeCrashesAfterShare() }
        sink.awaitIdle()

        assertFalse("its banner is down", crashed.exists())
        assertTrue("and its log is kept", File(dir, "androidlog-prev-1.log").exists())
    }
}
