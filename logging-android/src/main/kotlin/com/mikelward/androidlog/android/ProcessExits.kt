package com.mikelward.androidlog.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.safe
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Records why this app's recent processes ended, as pinned lines at the top of
 * the next run's log.
 *
 * An uncaught exception is the only process death an app sees from the inside.
 * Every other way a process ends leaves no trace in it: an ANR, a native crash,
 * a low-memory reclaim, the installer swapping the APK, an OEM's standby killing
 * it. From the next run those look alike, and like a clean exit: the log just
 * restarts. The platform keeps its own record ([ApplicationExitInfo]), and this
 * copies the last few into the log, with the package's update time beside them
 * so an update can be told from a failure.
 *
 * Shared because simmo, snoozemo, typelauncher and clothescast each carried a
 * copy of it that had begun to drift: one kept the platform's description and
 * one dropped it, one pinned its lines and the others let the ring evict them,
 * one wrote raw epoch millis a scrubber then masked. The lines here keep the
 * format those copies shared, so a report reads the same from any app.
 */
object ProcessExits {

    /** Enough to cover the run that went wrong without turning startup into a log dump. */
    const val DEFAULT_MAX_RECORDS: Int = 5

    /**
     * How much of the platform's description one exit line keeps. The only
     * field here with no bound of its own, so without this a batch has no size
     * a report can reserve room for (see [maxBatchChars]). The same bound
     * [DebugLog] gives a throwable's message, and long enough for an ANR's
     * "Input dispatching timed out (…)" sentence.
     */
    const val MAX_DESCRIPTION_CHARS: Int = 300

    /**
     * The most characters one [logRecent] batch of up to [maxRecords] exits
     * takes in the log, each line's timestamp, newline and offset anchor
     * included: what to pass as `pinnedBudgetChars` to
     * [DebugLog.boundedSnapshot] so a report carries the whole batch.
     *
     * A budget any smaller drops lines from the front of the batch, which is
     * its oldest exits: the batch is written oldest first so that any limit
     * the log applies costs the least useful line, not the exit that explains
     * this start. Add to it for any lines of the app's own that it also pins.
     */
    fun maxBatchChars(maxRecords: Int = DEFAULT_MAX_RECORDS): Int {
        requirePositive(maxRecords)
        // A count whose reserve can't be represented is refused rather than
        // wrapped: an overflowed Int is negative, and a negative reserve keeps
        // nothing (Codex, PR #50). A count meaning "all of them" has no reserve.
        require(maxRecords <= MAX_RESERVABLE_RECORDS) {
            "maxRecords must be at most $MAX_RESERVABLE_RECORDS to have a reserve, was $maxRecords"
        }
        return maxRecords * EXIT_LINE_CHARS + PACKAGE_LINE_CHARS
    }

    /**
     * Logs the package's own update and install times, then up to
     * [maxRecords] of its recent process exits oldest first, each as a
     * [DebugLog.pinnedEvent] on [log] so the ring doesn't evict them before a
     * report is shared. Least useful first, so any limit on pinned lines costs
     * the newest exit last.
     *
     * **Blocks on two binder calls**, so call it off the main thread, and after
     * the app has applied its stored recording setting to [log]: with recording
     * off it returns without querying anything.
     *
     * [includeDescription] adds the platform's free-text description of each
     * exit, cut to [MAX_DESCRIPTION_CHARS] and kept on one line. It is composed
     * by the system and can name another package, so it is off unless the app
     * has decided it wants it; even then it is passed as an ordinary argument,
     * so the floor renders it only in the device's own copy and withholds it
     * from anything leaving the device.
     *
     * A report built with [DebugLog.boundedSnapshot] keeps the whole batch when
     * it reserves [maxBatchChars] for pinned lines.
     *
     * Never throws for a failed query: one that fails is logged through
     * [DebugLog.failure] and leaves a pinned line saying so, since a missing
     * section would otherwise read like one that was never wired up.
     *
     * [maxRecords] must be positive. The platform reads 0 as "every record",
     * which no [maxBatchChars] reserve can hold.
     */
    fun logRecent(
        context: Context,
        log: DebugLog,
        includeDescription: Boolean = false,
        maxRecords: Int = DEFAULT_MAX_RECORDS,
    ) {
        requirePositive(maxRecords)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        record(
            log = log,
            includeDescription = includeDescription,
            // pid 0 means any process of this package; asking by pid would miss
            // exactly the abrupt deaths this is for.
            exits = activityManager?.let { manager ->
                // Taken to the count as well as asked for it, so the batch can
                // never outgrow the reserve [maxBatchChars] declares for it.
                {
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
                        .take(maxRecords)
                        .map(::toExit)
                }
            },
            packageTimes = {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                PackageTimes(lastUpdate = info.lastUpdateTime, firstInstall = info.firstInstallTime)
            },
        )
    }

    /** The fields of an [ApplicationExitInfo] this reads, so [record] runs without a device. */
    internal class Exit(
        val reason: Int,
        val importance: Int,
        val status: Int,
        val timestamp: Long,
        val description: String?,
    )

    internal class PackageTimes(val lastUpdate: Long, val firstInstall: Long)

    private fun toExit(info: ApplicationExitInfo) =
        Exit(info.reason, info.importance, info.status, info.timestamp, info.description)

    /**
     * The part [logRecent] shares between the apps, reachable without a
     * `Context`: this module's tests run on a plain JVM (see [DebugReport]).
     * [exits] answers newest first, as the platform does, and is null when
     * there is no `ActivityManager` to ask.
     */
    internal fun record(
        log: DebugLog,
        includeDescription: Boolean,
        exits: (() -> List<Exit>)?,
        packageTimes: () -> PackageTimes,
    ) {
        // Recording off means off: not even the queries, whose answers would
        // only be dropped. The queries are lambdas so this can decline them.
        if (!log.isRecording) return
        if (exits == null) {
            log.pinnedEvent("processExits unavailable reason=%s", safe("noActivityManager"))
            return
        }
        val records = try {
            exits()
        } catch (e: RuntimeException) {
            // A denial or a dead system_server: report it and return rather
            // than let it escape into the app's startup.
            log.failure(e, "processExits query failed")
            log.pinnedEvent("processExits unavailable reason=%s", safe("queryFailed"))
            return
        }
        // Least useful first. Every limit the log applies to pinned lines --
        // their count, a report's reserve -- drops from the front, so the
        // front holds what matters least and the end the exit that explains
        // this start (Codex, PR #50). The package times come first: they only
        // help read the exits, and a log keeping a single pinned line should
        // keep an exit, not them. logPackageTimes catches its own failures,
        // so going first can't cost the exits that follow.
        logPackageTimes(log, packageTimes)
        if (records.isEmpty()) {
            log.pinnedEvent("processExits none")
        } else {
            // Then the exits oldest first, reversing the platform's order. It
            // also reads like the rest of the log.
            records.asReversed().forEach { exit ->
                val fields = "processExit reason=%s importance=%s status=%s timestamp=%s"
                val named = arrayOf<Any?>(
                    safe(exitReasonName(exit.reason)),
                    safe(importanceName(exit.importance)),
                    exit.status,
                    safe(utcTimestamp(exit.timestamp)),
                )
                if (includeDescription) {
                    log.pinnedEvent("$fields description=%s", *named, boundedDescription(exit.description))
                } else {
                    log.pinnedEvent(fields, *named)
                }
            }
        }
    }

    private fun logPackageTimes(log: DebugLog, packageTimes: () -> PackageTimes) {
        val times = try {
            packageTimes()
        } catch (e: PackageManager.NameNotFoundException) {
            log.failure(e, "ownPackage query failed")
            log.pinnedEvent("ownPackage unavailable reason=%s", safe("notFound"))
            return
        } catch (e: RuntimeException) {
            // A binder call too, so a dead system_server fails it this way.
            log.failure(e, "ownPackage query failed")
            log.pinnedEvent("ownPackage unavailable reason=%s", safe("queryFailed"))
            return
        }
        log.pinnedEvent(
            "ownPackage lastUpdateTime=%s firstInstallTime=%s",
            safe(utcTimestamp(times.lastUpdate)),
            safe(utcTimestamp(times.firstInstall)),
        )
    }

    /**
     * A stable, readable name for an [ApplicationExitInfo] reason, since a report
     * is read by whoever it reaches, not only someone with the SDK constants to
     * hand. One this doesn't know keeps its number, so a newer platform's reason
     * still says something.
     */
    internal fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashNative"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencyDied"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessiveResourceUsage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exitSelf"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initializationFailure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "lowMemory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "packageStateChange"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "packageUpdated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissionChange"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "userRequested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "userStopped"
        else -> "unrecognized($reason)"
    }

    /**
     * A stable name for the importance the system gave the process when it
     * died. Foreground means the system counted it as work the user was aware
     * of, not that an activity was on screen: a service can be foreground with
     * nothing visible.
     */
    internal fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foregroundService"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "topSleeping"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "unrecognized($importance)"
    }

    /**
     * An epoch-millis time as a UTC instant with the month spelled out
     * (`2023-Nov-14T22:13:20Z`). Not raw millis, nor an all-digit ISO date: an
     * app's own scrubber (simmo's masks any run of six or more digits as a phone
     * number) would mask either, and comparing an exit with the package's update
     * time is the reason both are here. UTC, since a report is read anywhere.
     */
    internal fun utcTimestamp(epochMillis: Long): String =
        try {
            UTC_TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
        } catch (e: java.time.DateTimeException) {
            // Past what the formatter can print; the raw value still says something.
            "unrenderable($epochMillis)"
        }

    private val UTC_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MMM-dd'T'HH:mm:ss'Z'", Locale.US)

    /**
     * [description] cut to [MAX_DESCRIPTION_CHARS] and flattened onto one line.
     *
     * Cut before flattening, so a description of any length costs only the
     * prefix kept. Flattened because it is text the system composed: a newline
     * in it would open a line reading like one this log wrote.
     */
    internal fun boundedDescription(description: String?): String? {
        if (description == null) return null
        val overlong = description.length > MAX_DESCRIPTION_CHARS
        val head = if (overlong) description.take(MAX_DESCRIPTION_CHARS + 1) else description
        val flat = head.lineSequence().joinToString(" ") { it.trim() }.trim()
        if (!overlong) return flat
        // Stepped back off a high surrogate, so the cut never strands half a
        // pair. Checked even when the cut is at the end: the prefix read above
        // can itself end mid-pair, and flattening can shorten it to the bound
        // (Codex, PR #50).
        var cut = minOf(MAX_DESCRIPTION_CHARS, flat.length)
        if (cut > 0 && Character.isHighSurrogate(flat[cut - 1])) cut--
        return flat.take(cut) + TRUNCATED
    }

    private fun requirePositive(maxRecords: Int) =
        require(maxRecords > 0) { "maxRecords must be positive, was $maxRecords" }

    /** The marker [DebugLog] puts on anything it cuts, so a cut reads the same here. */
    private const val TRUNCATED = "…(truncated)"

    /** The longest a reason or importance renders: an unrecognized minimum `Int`. */
    private const val LONGEST_NAME_CHARS = 25 // "unrecognized(-2147483648)"

    /** The longest a status renders. */
    private const val LONGEST_STATUS_CHARS = 11 // "-2147483648"

    /** An upper bound on what [utcTimestamp] renders, its fallback included. */
    private const val LONGEST_TIMESTAMP_CHARS = 34 // "unrenderable(-9223372036854775808)"

    private const val MAX_EXIT_MESSAGE_CHARS =
        "processExit reason= importance= status= timestamp= description=".length +
            2 * LONGEST_NAME_CHARS + LONGEST_STATUS_CHARS + LONGEST_TIMESTAMP_CHARS +
            MAX_DESCRIPTION_CHARS + TRUNCATED.length

    private const val MAX_PACKAGE_MESSAGE_CHARS =
        "ownPackage lastUpdateTime= firstInstallTime=".length + 2 * LONGEST_TIMESTAMP_CHARS

    /**
     * What [DebugLog] adds around each message when it is counted against a
     * budget: the timestamp and level (21), the newline, and an offset anchor
     * (about 26). Over-reserved, since the log's line format is its own to
     * change; the tests hold this to what the real log renders.
     */
    private const val LINE_ALLOWANCE_CHARS = 64

    private const val EXIT_LINE_CHARS = MAX_EXIT_MESSAGE_CHARS + LINE_ALLOWANCE_CHARS

    private const val PACKAGE_LINE_CHARS = MAX_PACKAGE_MESSAGE_CHARS + LINE_ALLOWANCE_CHARS

    /** The largest count whose [maxBatchChars] fits in an `Int`. */
    private const val MAX_RESERVABLE_RECORDS = (Int.MAX_VALUE - PACKAGE_LINE_CHARS) / EXIT_LINE_CHARS
}
