package com.mikelward.androidlog.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = ProcessExits.record(log, includeDescription, exits, packageTimes)

    /** Lines about this, pinned ones only: the ring may evict the rest before a report. */
    private fun pinned(): List<String> =
        log.pinnedSnapshot().filter { "processExit" in it || "ownPackage" in it }

    @Test
    fun `each exit is pinned newest first with its reason and importance named`() {
        record({
            listOf(
                exit(ApplicationExitInfo.REASON_CRASH),
                exit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED),
            )
        })

        val lines = pinned()
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], lines[0].endsWith(
            "processExit reason=crash importance=foreground status=0 timestamp=1970-Jan-02T00:00:00Z",
        ))
        assertTrue(lines[1], lines[1].contains("reason=packageUpdated importance=cached"))
        assertTrue(lines[2], lines[2].endsWith(
            "ownPackage lastUpdateTime=1970-Jan-02T00:00:00Z firstInstallTime=1970-Jan-02T00:00:00Z",
        ))
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

        assertTrue(pinned().toString(), pinned()[0].endsWith("description=stopped by the installer"))
        // The named fields cross; only the platform's own text is held back.
        assertTrue(offDevice.toString(), offDevice.single().endsWith(
            "processExit reason=crash importance=foreground status=0 " +
                "timestamp=1970-Jan-02T00:00:00Z description=$OFF_DEVICE_PLACEHOLDER",
        ))
    }

    @Test
    fun `no exit records says so, rather than looking like a query never made`() {
        record({ emptyList() })

        assertEquals(pinned().toString(), 2, pinned().size)
        assertTrue(pinned()[0], pinned()[0].endsWith("processExits none"))
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
    fun `a failed package lookup keeps the exit records already logged`() {
        record({ listOf(exit(ApplicationExitInfo.REASON_ANR)) }, packageTimes = { throw IllegalStateException("dead") })

        val lines = pinned()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].contains("reason=anr"))
        assertTrue(lines[1], lines[1].endsWith("ownPackage unavailable reason=queryFailed"))
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
}
