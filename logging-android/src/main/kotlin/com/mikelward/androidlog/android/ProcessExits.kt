package com.mikelward.androidlog.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.safe
import java.io.IOException
import java.io.InputStream
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
     * How many of an ANR's main-thread frames [logRecent] keeps when asked to
     * ([includeAnrStack]): the top few, which say what the thread was stuck in,
     * then the first few of the app's own below them, which say whose code put
     * it there. A frozen screen is usually the app's own work, several frames
     * below a library's decoder or a framework's dispatch. See
     * [mainThreadFrames] for what counts as the app's.
     */
    const val ANR_TOP_FRAMES: Int = 8

    /** See [ANR_TOP_FRAMES]. */
    const val ANR_APP_FRAMES: Int = 6

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
    // 3.0's signatures, kept so code compiled against them (Java, or a library built on 3.0) still
    // links: a new defaulted parameter changes the JVM signature (Codex, PR #52). Hidden, so Kotlin
    // source resolves to the current ones and nothing new can call these.
    @Deprecated("3.0's signature, kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun maxBatchChars(maxRecords: Int = DEFAULT_MAX_RECORDS): Int = maxBatchChars(maxRecords, includeAnrStack = true)

    @Deprecated("3.0's signature, kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun logRecent(
        context: Context,
        log: DebugLog,
        includeDescription: Boolean = false,
        maxRecords: Int = DEFAULT_MAX_RECORDS,
    ) = logRecent(context, log, includeDescription, maxRecords, includeAnrStack = true)

    fun maxBatchChars(maxRecords: Int = DEFAULT_MAX_RECORDS, includeAnrStack: Boolean = true): Int {
        requirePositive(maxRecords)
        // A count whose reserve can't be represented is refused rather than
        // wrapped: an overflowed Int is negative, and a negative reserve keeps
        // nothing (Codex, PR #50). A count meaning "all of them" has no reserve.
        require(maxRecords <= MAX_RESERVABLE_RECORDS) {
            "maxRecords must be at most $MAX_RESERVABLE_RECORDS to have a reserve, was $maxRecords"
        }
        val stack = if (includeAnrStack) (ANR_TOP_FRAMES + ANR_APP_FRAMES) * STACK_LINE_CHARS else 0
        return maxRecords * EXIT_LINE_CHARS + PACKAGE_LINE_CHARS + stack
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
     * [includeAnrStack] adds, just before the newest ANR among them, where its
     * main thread was stuck: the frames [ANR_TOP_FRAMES] and [ANR_APP_FRAMES]
     * say, read from the trace the platform kept, one pinned line each. Before
     * its exit rather than after, so any limit on pinned lines, which drops
     * from the front, takes frames before the exit they belong to and never
     * leaves frames without it (Codex, PR #52). A frame is
     * a code location (class, method, file and line), never a value; each is
     * still passed as an ordinary argument, so it stays on the device's own
     * copy and is withheld from anything leaving it. Reading the trace is a
     * file read too, so the same off-the-main-thread rule applies. On by
     * default (maintainer, 2026-10-02): an ANR otherwise leaves only its reason
     * in the log, and the stack is what says which code froze the screen.
     * Reserve [maxBatchChars] with the same flag.
     *
     * [maxRecords] must be positive. The platform reads 0 as "every record",
     * which no [maxBatchChars] reserve can hold.
     */
    fun logRecent(
        context: Context,
        log: DebugLog,
        includeDescription: Boolean = false,
        maxRecords: Int = DEFAULT_MAX_RECORDS,
        includeAnrStack: Boolean = true,
    ) {
        requirePositive(maxRecords)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        record(
            log = log,
            includeDescription = includeDescription,
            includeAnrStack = includeAnrStack,
            appNamespace = appNamespaceOf(context),
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
        // The platform's trace for an ANR, read only when its stack is asked for; null when it kept none.
        val trace: () -> InputStream? = { null },
    )

    internal class PackageTimes(val lastUpdate: Long, val firstInstall: Long)

    private fun toExit(info: ApplicationExitInfo) =
        Exit(info.reason, info.importance, info.status, info.timestamp, info.description, trace = info::getTraceInputStream)

    /**
     * The part [logRecent] shares between the apps, reachable without a
     * `Context`: this module's tests run on a plain JVM (see [DebugReport]).
     * [exits] answers newest first, as the platform does, and is null when
     * there is no `ActivityManager` to ask. [includeAnrStack] asks for the
     * newest ANR's main-thread stack, [appNamespace] being where the app's own
     * classes live, when that can be told (see [mainThreadFrames]).
     */
    internal fun record(
        log: DebugLog,
        includeDescription: Boolean,
        exits: (() -> List<Exit>)?,
        includeAnrStack: Boolean = false,
        appNamespace: String? = null,
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
            val stackFor = if (includeAnrStack) records.firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR } else null
            records.asReversed().forEach { exit ->
                // Just before the exit it explains: it matters less than the exit, so any limit
                // on pinned lines, which drops from the front, takes it first.
                if (exit === stackFor) logAnrStack(log, exit, appNamespace)
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

    private fun logAnrStack(log: DebugLog, exit: Exit, appNamespace: String?) {
        val frames = try {
            exit.trace()?.bufferedReader()?.use { mainThreadFrames(it.lineSequence(), appNamespace) }
        } catch (e: IOException) {
            log.failure(e, "anr trace read failed")
            log.pinnedEvent("anrMainStack unavailable reason=%s", safe("readFailed"))
            return
        } catch (e: RuntimeException) {
            // A trace whose file the system has since dropped can fail this way too.
            log.failure(e, "anr trace read failed")
            log.pinnedEvent("anrMainStack unavailable reason=%s", safe("readFailed"))
            return
        }
        when {
            frames == null -> log.pinnedEvent("anrMainStack unavailable reason=%s", safe("noTrace"))
            frames.isEmpty() -> log.pinnedEvent("anrMainStack unavailable reason=%s", safe("noMainThread"))
            // Plain arguments: code locations, but read from a file the system wrote, so the
            // floor keeps them on the device's own copy. One line each: a log entry holds
            // 2,000 characters, not the 14 frames at their bound.
            else -> frames.forEach { (index, frame) -> log.pinnedEvent("anrMainStack #%s %s", index, frame) }
        }
    }

    /**
     * The frames of the `"main"` thread in an ANR trace worth keeping, with
     * each one's position in the stack: the top [ANR_TOP_FRAMES], then the
     * first [ANR_APP_FRAMES] of the app's own below them. Java frames (`at …`)
     * and the lock lines between them (`- waiting to lock …`) count; native
     * frames don't. Empty when the trace has no main thread.
     *
     * The app's own are those in [appNamespace], the package its `Application`
     * class is in (not its application ID, which a build suffix like `.debug`
     * changes). Where none is, as in a build whose classes R8 has renamed,
     * they are those outside the platform's own packages ([PLATFORM_PREFIXES]),
     * which no build renames: the app's code, or a library it bundles, either
     * way readable with the build's mapping file (Codex, PR #52).
     *
     * Reads only as far as the main thread's section, which a trace writes
     * first, so a trace of every thread costs no more than that one.
     */
    internal fun mainThreadFrames(lines: Sequence<String>, appNamespace: String?): List<Pair<Int, String>> {
        val section = lines
            .dropWhile { !it.startsWith("\"main\"") }
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .map { it.trim() }
            .filter { it.startsWith("at ") || it.startsWith("- ") }
            .map { boundedFrame(it.removePrefix("at ")) }
            .withIndex()
            .map { it.index to it.value }
            .toList()
        val top = section.take(ANR_TOP_FRAMES)
        val below = section.drop(ANR_TOP_FRAMES)
        val inNamespace = appNamespace?.let { namespace -> below.filter { it.second.startsWith("$namespace.") } }.orEmpty()
        val app = inNamespace.ifEmpty { below.filter { (_, frame) -> !frame.startsWith("- ") && PLATFORM_PREFIXES.none(frame::startsWith) } }
        return top + app.take(ANR_APP_FRAMES)
    }

    /**
     * The package the app's own classes are in: its `Application` subclass's,
     * which R8 keeps by name since the manifest names it. Null for the
     * platform's own `Application`, whose package says nothing about the app's.
     */
    private fun appNamespaceOf(context: Context): String? =
        context.applicationContext.javaClass.name.substringBeforeLast('.', "")
            .takeIf { namespace -> namespace.isNotEmpty() && PLATFORM_PREFIXES.none { "$namespace.".startsWith(it) } }

    /**
     * The platform's packages: the boot classpath and the libraries an app
     * build leaves as they are, so a frame in one is never the app's.
     */
    internal val PLATFORM_PREFIXES: List<String> = listOf(
        "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.", "android.", "com.android.",
        "androidx.", "kotlin.", "kotlinx.",
    )


    /**
     * [frame] cut to [MAX_FRAME_CHARS] and marked, so a line's size is bounded
     * for [maxBatchChars]: unlike a throwable's message, the log doesn't bound a
     * plain string argument itself. Stepped back off a high surrogate.
     */
    private fun boundedFrame(frame: String): String {
        if (frame.length <= MAX_FRAME_CHARS) return frame
        var cut = MAX_FRAME_CHARS
        if (Character.isHighSurrogate(frame[cut - 1])) cut--
        return frame.take(cut) + TRUNCATED
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

    /** The longest one frame line keeps of a frame, before the cut's marker. */
    private const val MAX_FRAME_CHARS = 300

    /** One stack line: its fixed text, the frame's number (at most an `Int`), and the bounded frame. */
    private const val STACK_LINE_CHARS =
        "anrMainStack # ".length + 11 + MAX_FRAME_CHARS + TRUNCATED.length + LINE_ALLOWANCE_CHARS

    /** The largest count whose [maxBatchChars] fits in an `Int`. */
    private const val MAX_RESERVABLE_RECORDS =
        (Int.MAX_VALUE - PACKAGE_LINE_CHARS - (ANR_TOP_FRAMES + ANR_APP_FRAMES) * STACK_LINE_CHARS) / EXIT_LINE_CHARS
}
