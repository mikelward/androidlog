package com.mikelward.androidlog.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared half of [ProcessExits.logRecent], on a plain JVM: what reaches the
 * log for a given set of exit records, and what each name means. The two binder
 * calls that feed it are a consuming app's to exercise under Robolectric (this
 * module stays Robolectric-free, see [DebugReport]).
 */
class ProcessExitsTest {

    private val log = DebugLog(readMillis = { 0L })

    // One day after the epoch; a synthetic time, spelled out in the assertions.
    private val dayOne = 86_400_000L

    private fun exit(
        reason: Int,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        description: String? = "stopped by the installer",
    ) = ProcessExits.Exit(reason, importance, status = 0, timestamp = dayOne, description = description)

    private val times = ProcessExits.PackageTimes(lastUpdate = dayOne, firstInstall = dayOne)

    private fun record(
        exits: (() -> List<ProcessExits.Exit>)?,
        includeDescription: Boolean = false,
        packageTimes: () -> ProcessExits.PackageTimes = { times },
    ) = ProcessExits.record(log, includeDescription, exits, packageTimes = packageTimes)

    /** Lines about this, pinned ones only: the ring may evict the rest before a report. */
    private fun pinned(): List<String> =
        log.pinnedSnapshot().filter { "processExit" in it || "ownPackage" in it }

    @Test
    fun `the package times are pinned first, then each exit oldest first with its reason and importance named`() {
        // Newest first, as the platform answers.
        record({
            listOf(
                exit(ApplicationExitInfo.REASON_CRASH),
                exit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED),
            )
        })

        val lines = pinned()
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], lines[0].endsWith(
            "ownPackage lastUpdateTime=1970-Jan-02T00:00:00Z firstInstallTime=1970-Jan-02T00:00:00Z",
        ))
        assertTrue(lines[1], lines[1].contains("reason=packageUpdated importance=cached"))
        assertTrue(lines[2], lines[2].endsWith(
            "processExit reason=crash importance=foreground status=0 timestamp=1970-Jan-02T00:00:00Z",
        ))
    }

    /** Five exits, newest first as the platform answers: the newest is the ANR. */
    private val fiveExits = listOf(
        exit(ApplicationExitInfo.REASON_ANR),
        exit(ApplicationExitInfo.REASON_CRASH),
        exit(ApplicationExitInfo.REASON_LOW_MEMORY),
        exit(ApplicationExitInfo.REASON_USER_STOPPED),
        exit(ApplicationExitInfo.REASON_PACKAGE_UPDATED),
    )

    @Test
    fun `a pinned buffer too small for the batch loses the package times and oldest exits, not the newest`() {
        val small = DebugLog(maxPinnedEntries = 3, readMillis = { 0L })

        ProcessExits.record(small, includeDescription = false, { fiveExits }) { times }

        val lines = small.pinnedSnapshot().filter { "processExit" in it || "ownPackage" in it }
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], "reason=lowMemory" in lines[0])
        assertTrue(lines[1], "reason=crash" in lines[1])
        assertTrue(lines[2], "reason=anr" in lines[2])
    }

    @Test
    fun `a single pinned entry keeps the newest exit`() {
        val one = DebugLog(maxPinnedEntries = 1, readMillis = { 0L })

        ProcessExits.record(one, includeDescription = false, { fiveExits }) { times }

        val lines = one.pinnedSnapshot().filter { "processExit" in it || "ownPackage" in it }
        assertTrue(lines.toString(), lines.single().contains("reason=anr"))
    }

    @Test
    fun `a report reserve too small for the batch loses the oldest exits, not the newest`() {
        val log = DebugLog(maxEntries = 10, readMillis = { 0L })
        ProcessExits.record(log, includeDescription = false, { fiveExits }) { times }
        repeat(20) { log.event("later %s", it) }
        // Room for about half the batch.
        val full = log.boundedSnapshot(ProcessExits.maxBatchChars(), 0).sumOf { it.length + 1 }
        val short = log.boundedSnapshot(pinnedBudgetChars = full / 2, recentBudgetChars = 0)

        assertTrue(short.toString(), short.last().contains("reason=anr"))
        assertFalse(short.toString(), short.any { "reason=packageUpdated" in it })
    }

    @Test
    fun `the description is left out unless asked for`() {
        record({ listOf(exit(ApplicationExitInfo.REASON_CRASH)) })

        assertFalse(pinned().toString(), pinned().any { "description" in it })
    }

    @Test
    fun `an asked-for description stays on the device and is withheld from what leaves it`() {
        val offDevice = mutableListOf<String>()
        log.addSink({ if ("processExit " in it) offDevice += it }, DebugLog.Destination.OFF_DEVICE)

        record({ listOf(exit(ApplicationExitInfo.REASON_CRASH)) }, includeDescription = true)

        assertTrue(pinned().toString(), pinned().single { "processExit " in it }.endsWith("description=stopped by the installer"))
        // The named fields cross; only the platform's own text is held back.
        assertTrue(offDevice.toString(), offDevice.single().endsWith(
            "processExit reason=crash importance=foreground status=0 " +
                "timestamp=1970-Jan-02T00:00:00Z description=$OFF_DEVICE_PLACEHOLDER",
        ))
    }

    @Test
    fun `a long description is cut to its bound and kept on one line`() {
        val long = "Input dispatching timed out\n" + "x".repeat(2 * ProcessExits.MAX_DESCRIPTION_CHARS)

        record({ listOf(exit(ApplicationExitInfo.REASON_ANR, description = long)) }, includeDescription = true)

        val line = pinned().single { "processExit " in it }
        val description = line.substringAfter("description=")
        assertTrue(line, description.startsWith("Input dispatching timed out xxx"))
        assertTrue(line, description.endsWith("…(truncated)"))
        assertEquals(line, ProcessExits.MAX_DESCRIPTION_CHARS, description.removeSuffix("…(truncated)").length)
        assertFalse(line, "\n" in line)
    }

    @Test
    fun `a cut never leaves half a surrogate pair, even where flattening shortened the text to the bound`() {
        // The prefix read ends on the emoji's high surrogate; dropping the
        // leading newline then brings the flattened text to exactly the bound.
        val description = "\n" + "a".repeat(ProcessExits.MAX_DESCRIPTION_CHARS - 1) + "\uD83D\uDE00" + "b".repeat(50)

        val bounded = ProcessExits.boundedDescription(description)!!.removeSuffix("…(truncated)")

        assertEquals("a".repeat(ProcessExits.MAX_DESCRIPTION_CHARS - 1), bounded)
    }

    @Test
    fun `a record count the platform would read as every record is refused`() {
        // getHistoricalProcessExitReasons reads 0 as no limit, which no
        // reserve can be sized for.
        assertThrows(IllegalArgumentException::class.java) { ProcessExits.maxBatchChars(0) }
        assertThrows(IllegalArgumentException::class.java) { ProcessExits.maxBatchChars(-1) }
    }

    @Test
    fun `a record count too large to reserve for is refused rather than overflowing`() {
        assertThrows(IllegalArgumentException::class.java) { ProcessExits.maxBatchChars(Int.MAX_VALUE) }
        // Right at the edge: the largest count whose reserve fits is accepted
        // and positive, and one more is refused.
        val perExit = ProcessExits.maxBatchChars(2) - ProcessExits.maxBatchChars(1)
        val packageLine = ProcessExits.maxBatchChars(1) - perExit
        val largest = (Int.MAX_VALUE - packageLine) / perExit
        assertTrue("$largest", ProcessExits.maxBatchChars(largest) > 0)
        assertThrows(IllegalArgumentException::class.java) { ProcessExits.maxBatchChars(largest + 1) }
    }

    @Test
    fun `a short description is kept whole, only flattened`() {
        assertEquals("one two", ProcessExits.boundedDescription("one\n  two"))
        assertEquals(null, ProcessExits.boundedDescription(null))
    }

    @Test
    fun `a full batch at its longest fits the reserve it declares, after the ring has dropped it`() {
        // A small ring, so a handful of later lines evicts the whole batch and
        // only the pinned copy can carry it into a report.
        val log = DebugLog(maxEntries = 10, readMillis = { 0L })
        val longest = List(ProcessExits.DEFAULT_MAX_RECORDS) {
            ProcessExits.Exit(
                reason = Int.MIN_VALUE,
                importance = Int.MIN_VALUE,
                status = Int.MIN_VALUE,
                timestamp = Long.MIN_VALUE,
                description = "y".repeat(10 * ProcessExits.MAX_DESCRIPTION_CHARS),
            )
        }
        ProcessExits.record(log, includeDescription = true, { longest }) {
            ProcessExits.PackageTimes(lastUpdate = Long.MIN_VALUE, firstInstall = Long.MIN_VALUE)
        }
        repeat(20) { log.event("later %s", it) }
        // Precondition: the ring alone has lost every line of the batch.
        assertFalse(log.snapshot().any { "processExit" in it || "ownPackage" in it })

        val kept = log.boundedSnapshot(pinnedBudgetChars = ProcessExits.maxBatchChars(includeAnrStack = false), recentBudgetChars = 0)

        val exits = kept.filter { "processExit " in it }
        assertEquals(kept.toString(), ProcessExits.DEFAULT_MAX_RECORDS, exits.size)
        // Whole, not clamped to fit.
        exits.forEach { assertTrue(it, it.endsWith("…(truncated)") && "unrecognized(-2147483648)" in it) }
        assertTrue(kept.toString(), kept.any { "ownPackage lastUpdateTime=" in it && "firstInstallTime=" in it })
        // And not so loose that it takes a report's space for nothing: within
        // one line's allowance per line of what the batch really renders to.
        val rendered = kept.sumOf { it.length + 1 }
        val reserve = ProcessExits.maxBatchChars(includeAnrStack = false)
        assertTrue("$rendered of $reserve", reserve - rendered < 64 * 6)
    }

    /** A trace in the platform's shape: a header, the main thread, then another thread. */
    private fun trace(mainFrames: List<String>) = buildString {
        appendLine("----- pid 1234 at 2026-10-02 00:00:00.000 -----")
        appendLine("DALVIK THREADS (2):")
        appendLine("\"main\" prio=5 tid=1 Runnable")
        appendLine("  | group=\"main\" sCount=0 ucsCount=0 flags=0 obj=0x0 self=0x0")
        appendLine("  native: #00 pc 0000 /apex/libc.so (read+8)")
        mainFrames.forEach { appendLine("  $it") }
        appendLine()
        appendLine("\"worker\" prio=5 tid=2 Waiting")
        appendLine("  at com.example.app.Worker.run(Worker.kt:1)")
    }

    // A deep main thread: ten library frames, then the app's own, as a decode on the main thread looks.
    private val deepMain = List(10) { "at lib.Decoder.read$it(Decoder.kt:$it)" } +
        "- waiting to lock <0x0> (a java.lang.Object) held by thread 2" +
        List(8) { "at com.example.app.Screen.load$it(Screen.kt:$it)" } +
        "at android.os.Looper.loop(Looper.java:1)"

    private fun anrWith(trace: String?) = ProcessExits.Exit(
        ApplicationExitInfo.REASON_ANR, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        status = 0, timestamp = dayOne, description = null,
        trace = { trace?.byteInputStream() },
    )

    @Test
    fun `an ANR's main thread keeps its top frames, then the app's own below them, and nothing of other threads`() {
        val frames = ProcessExits.mainThreadFrames(trace(deepMain).lineSequence(), "com.example.app")

        assertEquals((0 until 8).map { "lib.Decoder.read$it(Decoder.kt:$it)" }, frames.take(8).map { it.second })
        // Below the top: only the app's, and only so many; the lock line and the looper aren't the app's.
        assertEquals((0 until 6).map { "com.example.app.Screen.load$it(Screen.kt:$it)" }, frames.drop(8).map { it.second })
        // Numbered by their place in the stack, the lock line included.
        assertEquals((0 until 8).toList() + (11 until 17).toList(), frames.map { it.first })
        assertTrue(frames.none { "Worker" in it.second || "libc" in it.second })
    }

    @Test
    fun `a trace with no main thread keeps no frames`() {
        assertEquals(emptyList<Pair<Int, String>>(), ProcessExits.mainThreadFrames("nothing here".lineSequence(), "com.example.app"))
    }

    @Test
    fun `under a build suffix or renamed classes, the app's frames are those outside the platform`() {
        // Renamed by R8: nothing in the namespace, so the first non-platform frames below the top.
        val renamed = List(10) { "at kotlinx.Decoder.read$it(Decoder.kt:$it)" } +
            "at androidx.compose.Recomposer.run(Recomposer.kt:1)" +
            List(8) { "at a.b.c$it(SourceFile:$it)" }
        val frames = ProcessExits.mainThreadFrames(trace(renamed).lineSequence(), "com.example.app")
        assertEquals((0 until 6).map { "a.b.c$it(SourceFile:$it)" }, frames.drop(8).map { it.second })
        // No namespace at all (the platform's own Application): the same.
        assertEquals(frames, ProcessExits.mainThreadFrames(trace(renamed).lineSequence(), null))
    }

    @Test
    fun `the newest ANR's stack comes just before its exit, on the device's own copy only`() {
        val offDevice = mutableListOf<String>()
        log.addSink({ if ("anrMainStack" in it) offDevice += it }, DebugLog.Destination.OFF_DEVICE)

        // Newest first: this start's ANR, then an older one whose trace mustn't be read.
        ProcessExits.record(log, includeDescription = false, {
            listOf(anrWith(trace(deepMain)), anrWith(null), exit(ApplicationExitInfo.REASON_CRASH))
        }, includeAnrStack = true, appNamespace = "com.example.app") { times }

        val lines = log.pinnedSnapshot().filter { "processExit" in it || "anrMainStack" in it }
        // The crash, the older ANR with no stack, then the newest ANR's 14 frames and its exit.
        assertEquals(lines.toString(), 3 + 14, lines.size)
        assertTrue(lines[1], "reason=anr" in lines[1])
        assertTrue(lines[2], lines[2].endsWith("anrMainStack #0 lib.Decoder.read0(Decoder.kt:0)"))
        assertTrue(lines[15], lines[15].endsWith("anrMainStack #16 com.example.app.Screen.load5(Screen.kt:5)"))
        assertTrue(lines.last(), "reason=anr" in lines.last())
        assertTrue(lines.none { "unavailable" in it })
        // A frame crosses only as the placeholder.
        assertEquals(14, offDevice.size)
        assertTrue(offDevice.toString(), offDevice.all { it.endsWith(OFF_DEVICE_PLACEHOLDER) })
    }

    @Test
    fun `a pinned buffer too small for the stack and its exit keeps the exit`() {
        val one = DebugLog(maxPinnedEntries = 1, readMillis = { 0L })

        ProcessExits.record(one, includeDescription = false, { listOf(anrWith(trace(deepMain))) }, includeAnrStack = true, appNamespace = "com.example.app") { times }

        val kept = one.pinnedSnapshot().filter { "processExit" in it || "anrMainStack" in it }
        assertTrue(kept.toString(), kept.single().contains("reason=anr"))
    }

    @Test
    fun `an ANR with no trace, or one that can't be read, says so`() {
        ProcessExits.record(log, includeDescription = false, { listOf(anrWith(null)) }, includeAnrStack = true, appNamespace = "com.example.app") { times }
        assertTrue(pinned().toString(), log.pinnedSnapshot().any { it.endsWith("anrMainStack unavailable reason=noTrace") })

        val broken = ProcessExits.Exit(
            ApplicationExitInfo.REASON_ANR, 0, 0, dayOne, null,
            trace = { throw java.io.IOException("gone") },
        )
        ProcessExits.record(log, includeDescription = false, { listOf(broken) }, includeAnrStack = true, appNamespace = "com.example.app") { times }
        assertTrue(log.pinnedSnapshot().any { it.endsWith("anrMainStack unavailable reason=readFailed") })
    }

    @Test
    fun `without the stack asked for, a trace is never read`() {
        var read = false
        val anr = ProcessExits.Exit(ApplicationExitInfo.REASON_ANR, 0, 0, dayOne, null, trace = { read = true; null })

        ProcessExits.record(log, includeDescription = false, { listOf(anr) }, includeAnrStack = false) { times }

        assertFalse(read)
        assertFalse(log.pinnedSnapshot().any { "anrMainStack" in it })
    }

    @Test
    fun `a batch with the longest ANR stack fits the reserve it declares`() {
        val log = DebugLog(maxEntries = 10, readMillis = { 0L })
        val huge = List(30) { "at com.example.app.${"Z".repeat(1_000)}$it(Z.kt:$it)" }
        val exits = listOf(anrWith(trace(huge))) + List(ProcessExits.DEFAULT_MAX_RECORDS - 1) {
            exit(Int.MIN_VALUE, Int.MIN_VALUE, description = "y".repeat(10 * ProcessExits.MAX_DESCRIPTION_CHARS))
        }
        ProcessExits.record(log, includeDescription = true, { exits }, includeAnrStack = true, appNamespace = "com.example.app") {
            ProcessExits.PackageTimes(lastUpdate = Long.MIN_VALUE, firstInstall = Long.MIN_VALUE)
        }
        repeat(20) { log.event("later %s", it) }

        val kept = log.boundedSnapshot(pinnedBudgetChars = ProcessExits.maxBatchChars(), recentBudgetChars = 0)

        val stack = kept.filter { "anrMainStack #" in it }
        assertEquals(kept.toString(), ProcessExits.ANR_TOP_FRAMES + ProcessExits.ANR_APP_FRAMES, stack.size)
        assertEquals(ProcessExits.DEFAULT_MAX_RECORDS, kept.count { "processExit " in it })
        stack.forEach { assertTrue(it, it.endsWith("…(truncated)")) }
        // And not so loose it takes a report's space for nothing: within a line's allowance each.
        val rendered = kept.sumOf { it.length + 1 }
        assertTrue("$rendered of ${ProcessExits.maxBatchChars()}", ProcessExits.maxBatchChars() - rendered < 64 * 20 + 14 * 12)
    }

    @Test
    fun `no exit records says so, rather than looking like a query never made`() {
        record({ emptyList() })

        assertEquals(pinned().toString(), 2, pinned().size)
        assertTrue(pinned()[1], pinned()[1].endsWith("processExits none"))
    }

    @Test
    fun `a failed query is reported and pinned, and doesn't escape`() {
        record({ throw SecurityException("denied") })

        assertTrue(pinned().toString(), pinned().single().endsWith("processExits unavailable reason=queryFailed"))
        assertTrue(log.snapshot().toString(), log.snapshot().any { "processExits query failed" in it })
    }

    @Test
    fun `no ActivityManager is pinned as its own reason`() {
        record(exits = null)

        assertTrue(pinned().toString(), pinned().single().endsWith("processExits unavailable reason=noActivityManager"))
    }

    @Test
    fun `a failed package lookup still logs every exit`() {
        record({ listOf(exit(ApplicationExitInfo.REASON_ANR)) }, packageTimes = { throw IllegalStateException("dead") })

        val lines = pinned()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].endsWith("ownPackage unavailable reason=queryFailed"))
        assertTrue(lines[1], lines[1].contains("reason=anr"))
    }

    @Test
    fun `with recording off nothing is queried or logged`() {
        log.setRecording(false)
        var asked = false

        record({ asked = true; emptyList() }, packageTimes = { asked = true; times })

        assertFalse(asked)
        assertTrue(log.snapshot().toString(), log.snapshot().isEmpty())
    }

    @Test
    fun `names the reasons that separate an app's failures from the platform's reclaims`() {
        // Ours to fix.
        assertEquals("crash", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("crashNative", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("anr", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_ANR))
        // The platform's doing.
        assertEquals("lowMemory", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("packageUpdated", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("userStopped", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_USER_STOPPED))
        // A newer platform's reason keeps its number rather than reading as unknown.
        assertEquals("unknown", ProcessExits.exitReasonName(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals("unrecognized(9999)", ProcessExits.exitReasonName(9999))
    }

    @Test
    fun `names the importance the process died at`() {
        assertEquals(
            "foreground",
            ProcessExits.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
        )
        assertEquals("cached", ProcessExits.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertEquals("gone", ProcessExits.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE))
        assertEquals("unrecognized(7)", ProcessExits.importanceName(7))
    }

    @Test
    fun `a timestamp has no run of digits an app's number scrubber would mask`() {
        // simmo masks six or more digits in a row as a phone number: raw millis,
        // or an all-digit ISO date, would arrive masked.
        val stamp = ProcessExits.utcTimestamp(1_700_000_000_000L)

        assertEquals("2023-Nov-14T22:13:20Z", stamp)
        assertFalse(stamp, Regex("\\d{6,}").containsMatchIn(stamp))
    }

    @Test
    fun `3_0's signatures still link, and reserve for the stack that is now on by default`() {
        val exits = ProcessExits::class.java
        val oneArg = exits.getMethod("maxBatchChars", Int::class.javaPrimitiveType)
        assertEquals(ProcessExits.maxBatchChars(3), oneArg.invoke(ProcessExits, 3))
        // The old default-argument bridges too, which a caller relying on a default compiled against.
        assertTrue(exits.methods.any { it.name == "maxBatchChars\$default" && it.parameterCount == 4 })
        exits.getMethod(
            "logRecent",
            android.content.Context::class.java,
            com.mikelward.androidlog.DebugLog::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
    }

}
