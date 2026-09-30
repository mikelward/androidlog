package com.mikelward.androidlog.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mikelward.androidlog.DebugLog

/**
 * What a share attempt actually achieved.
 *
 * Reported rather than assumed, because the two delivery routes fail
 * independently and the difference decides what the user must be told — and
 * whether the prior run may be consumed. `ACTION_SEND` gives no send or
 * selection callback, so a launched chooser is not proof anything was sent;
 * only the clipboard copy is a delivery the user can still get at afterwards.
 */
enum class ShareOutcome {
    /** The chooser opened. Its own confirmation — the user needs no other. */
    SHARED,

    /**
     * The clipboard has the report but no chooser opened. **Say so.** The user
     * saw no share sheet and no error, so without a word they assume the tap did
     * nothing and share again — and the second report carries no prior run,
     * overwriting the clipboard copy that did.
     */
    COPIED_ONLY,

    /**
     * Neither route landed. **Say so.** Nothing happened that the user can see,
     * and they will retry into the same silence.
     */
    FAILED,
}

/**
 * A built report, and the prior run it actually carries.
 *
 * The pairing is the point: [DebugReport.deliver] consumes that run only once
 * the report containing it is somewhere the user can still get it. A report
 * built without one — the fallback below — carries `null` and so can never
 * consume anything, which is what stops a failed collection from deleting the
 * crash log the user tapped Share for.
 */
class CollectedReport internal constructor(
    val text: String,
    internal val run: PreviousRun?,
    /**
     * The sink the run came from, carried rather than asked for again.
     *
     * [DebugReport.deliver] used to take its own sink parameter, which let a
     * caller collect from one and deliver against another — or against null,
     * leaving a delivered run unconsumed and appended to the next report too
     * (Codex, PR #8). Two arguments that must agree are two arguments that can
     * disagree; one that travels with the run cannot.
     */
    internal val sink: DebugFileSink?,
)

/**
 * Delivering a debug report: the two routes, the failure the user is told
 * about, and the rule for when the prior run may be consumed.
 *
 * **What the report says is the app's, not this library's** — a decision
 * snapshot, a snooze summary, a rule listing are each app domain, and
 * `AGENTS.md` keeps them out of here. What is shared is the mechanism around
 * them, which is identical in every app and was where the copies had already
 * grown different answers to the same question.
 *
 * **Split in two, and blocking rather than `suspend`.** [collect] belongs on a
 * background thread; [deliver] touches the clipboard and belongs on the main
 * one. Every consumer already has a coroutine scope to compose them with, and
 * that is a better place for the choice of dispatcher than a library that
 * cannot take a dependency to express it — `Dispatchers` and `withContext` are
 * `kotlinx-coroutines`, which this library does not have.
 *
 * `suspend` *itself* would cost nothing: the keyword, `Continuation` and
 * `suspendCoroutine` are all `kotlin-stdlib`. A later version could park the
 * caller while the worker reads and resume them on whatever dispatcher they
 * came from, which would remove the "call it off the main thread" footgun
 * below; see `TODO.md`. It is not that today because this is the shape the
 * consuming apps already wrap.
 */
object DebugReport {

    /**
     * The most a report may hold, the earlier runs included.
     *
     * A report has to survive two Binder transfers, and it is the second that
     * sets the bound. The clipboard copy carries the text once, as UTF-16 —
     * two bytes a character. A text-only share carries it twice: starting the
     * chooser copies `EXTRA_TEXT` into the intent's `ClipData`
     * (`Intent.migrateExtraStreamToClipData`), so both copies ride in one
     * transaction, four bytes a character. At this size that is about 240 KB of
     * the 1 MB buffer the whole process shares with every other call in flight
     * — room to spare on a busy device, where a report several times this size
     * failed on both routes. (A screenshot report sets its own `ClipData`, so
     * the migration is skipped and its text crosses once.)
     *
     * Enforced in [collect], so every [CollectedReport] is already inside it:
     * a cut at delivery could not know which part of the text is the prior run
     * the report consumes.
     */
    const val MAX_REPORT_CHARS = 60_000

    /**
     * The part of [MAX_REPORT_CHARS] the earlier runs may use, their heading
     * and the blank line before it included.
     *
     * A fixed share rather than whatever the app's section leaves over, so the
     * runs are read — and a failure to read them logged — before the app's
     * section is built, where that section's own log can still carry the line.
     * The rest is the app's: a section within
     * `MAX_REPORT_CHARS - MAX_EARLIER_RUNS_CHARS` always arrives whole, and one
     * with no earlier runs beside it can use all of [MAX_REPORT_CHARS].
     *
     * The newest part of the runs is kept, so what goes is the oldest: a crash
     * leaves its stack trace and the lines leading up to it at the end of its
     * run.
     */
    const val MAX_EARLIER_RUNS_CHARS = 20_000

    /**
     * Reads the prior run, builds the report from it, and remembers whether the
     * text really carries it. **Blocks** — call it off the main thread.
     *
     * [buildPayload] writes what the *app* wants to say — its own state, in its
     * own words. The prior run is **appended by this function**, under
     * [heading], rather than passed in for the builder to include: that is what
     * makes carrying it and consuming it the same fact rather than two facts
     * that can disagree. An app wanting no prior run in its report passes a
     * null [sink], which reads nothing and so consumes nothing.
     *
     * A failure inside [buildPayload] is contained: a report is most useful
     * after something has already gone wrong, so a failure while inspecting
     * that state must never become a second one. The app's section then names
     * the failure's *type* only — a stand-in, not a floor: the failure is
     * recorded through [log] one line earlier, with its message, into the run
     * this report is already carrying. And the prior run is still appended and
     * still consumed, because it is still there to read.
     *
     * The report is held to [MAX_REPORT_CHARS]. The earlier runs are read to
     * fit [MAX_EARLIER_RUNS_CHARS], which keeps the files whose lines were all
     * trimmed away out of what the report consumes; the app's section gets the
     * rest, and is cut from the middle only if it outgrows that ([keepingEnds]).
     * An app that budgets its own section never sees the cut.
     */
    fun collect(
        log: DebugLog,
        sink: DebugFileSink?,
        heading: String = "--- earlier runs ---",
        buildPayload: () -> String,
    ): CollectedReport {
        // The run's share less its framing -- the blank line before the
        // heading and the newline after it -- so the section as a whole stays
        // inside [MAX_EARLIER_RUNS_CHARS].
        val runBudget = MAX_EARLIER_RUNS_CHARS - (heading.length + 3)
        val run = when {
            sink == null -> null
            runBudget <= 0 -> {
                // Reading nothing consumes nothing, so the runs wait for a
                // report with room for them rather than going out empty.
                log.warning("An earlier-runs heading of %s characters leaves no room for the runs", heading.length)
                null
            }
            // Bounded in the read, not after it: a trim of the finished text
            // would leave the handle consuming runs the report no longer
            // carries, which is how a crash log gets deleted unsent.
            else -> runCatching { sink.readPreviousRun(runBudget) }
                .onFailure { log.failure(it, "Earlier runs could not be read for a report") }
                .getOrNull()
        }
        val own = runCatching { buildPayload() }
            .getOrElse { failure ->
                log.failure(failure, "A report could not be built")
                "[the report could not be built: ${failure.javaClass.name}]"
            }
        // Appended here rather than handed to [buildPayload], so that carrying
        // the run and consuming it are the same fact.
        //
        // Passing it in and trusting the returned text to contain it is the
        // shape that loses a crash log: a payload builder that ignores its
        // argument — an app reporting current state only, or one that simply
        // forgets — still gets the run marked as delivered, and the first
        // successful clipboard copy deletes a diagnostic nobody ever saw
        // (Codex, PR #8). Nothing the library can inspect distinguishes that
        // from an honest inclusion, so it does not have to: the library writes
        // the section itself.
        val text = if (run == null) {
            keepingEnds(own, MAX_REPORT_CHARS)
        } else {
            val section = "$heading\n${run.text}"
            "${keepingEnds(own, MAX_REPORT_CHARS - section.length - 2)}\n\n$section"
        }
        return CollectedReport(text, run, sink)
    }

    /**
     * [text] within [budgetChars], cut from the middle when it has to be.
     *
     * The backstop for an app section that outgrew its share of the report —
     * an app that budgets its own sections never reaches it. The middle goes
     * because this library cannot know an app's layout, and the ends are what
     * every app puts somewhere worth keeping: the head names the build and the
     * state, the tail is the newest of the log. Half the room is the head's
     * and the rest the tail's, and a line between them says how many
     * characters went, so a reader knows the report is not the whole account.
     *
     * Each cut moves to a nearby line break, so no line is left half-read —
     * unless the nearest is further away than a log entry can be long, when it
     * stays mid-line rather than give up the room. Counted in characters, not
     * lines, for that reason: one line longer than the whole budget still
     * keeps both its ends (Codex, PR #51).
     */
    internal fun keepingEnds(text: String, budgetChars: Int): String {
        if (text.length <= budgetChars) return text
        // Sized for the largest count it could name, so the final form fits
        // whatever the count turns out to be. + a newline either side.
        val room = budgetChars - (cutNotice(text.length).length + 2)
        if (room <= 0) return text.substring(0, codePointCut(text, budgetChars))
        var headEnd = codePointCut(text, room / 2)
        // Inclusive of headEnd itself: a break there means the head already
        // ends on a whole line.
        val lineEnd = text.lastIndexOf('\n', headEnd)
        if (lineEnd >= 0 && headEnd - lineEnd <= DebugLog.DEFAULT_MAX_ENTRY_CHARS) headEnd = lineEnd
        // Whatever the head did not use goes to the tail.
        var tailStart = text.length - (room - headEnd)
        if (text[tailStart].isLowSurrogate()) tailStart++
        if (text[tailStart - 1] != '\n') {
            val lineStart = text.indexOf('\n', tailStart) + 1
            if (lineStart > 0 && lineStart - tailStart <= DebugLog.DEFAULT_MAX_ENTRY_CHARS) tailStart = lineStart
        }
        return listOf(text.substring(0, headEnd), cutNotice(tailStart - headEnd), text.substring(tailStart))
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun cutNotice(chars: Int) = "[$chars characters cut here to keep the report shareable]"

    /** Where to cut [text] near [at] without splitting a surrogate pair. */
    private fun codePointCut(text: String, at: Int): Int {
        val cut = at.coerceIn(0, text.length)
        return if (cut > 0 && text[cut - 1].isHighSurrogate()) cut - 1 else cut
    }

    /**
     * Copies the report to the clipboard, opens the share chooser, and consumes
     * the prior run only if that landed somewhere retained. **Touches the
     * clipboard — call it on the main thread.**
     *
     * The clear is gated on the *clipboard*, not the chooser, for the reason in
     * [ShareOutcome]: the chooser reports nothing back, so treating its launch
     * as delivery would consume a crash log on the strength of a sheet the user
     * may have dismissed. Both routes are attempted regardless of whether the
     * other worked.
     *
     * An optional [screenshot] rides along in the chooser as an `image/png`
     * attachment, so a report can show what the user was looking at. It is a
     * `content://` [Uri] the **app** mints — from its own `FileProvider`, whose
     * authority and paths only it can declare — because a screenshot is the
     * app's own content just as the report text is, and owning the provider here
     * would force resources on every consumer for a picture only some of them
     * send. Passing it flips the intent's type to `image/png` and grants the
     * chooser's target read access to the URI; omitting it (the default) shares
     * text only, exactly as before. The clipboard still carries the text alone —
     * it is the retained fallback the outcome is gated on, and an image is not
     * something a paste can recover.
     */
    @JvmOverloads
    fun deliver(
        context: Context,
        log: DebugLog,
        report: CollectedReport,
        subject: String,
        chooserTitle: String,
        clipboardLabel: String,
        screenshot: Uri? = null,
    ): ShareOutcome {
        val copied = copyToClipboard(context, log, clipboardLabel, report.text)
        val launched =
            startChooser(context, log, subject, chooserTitle, clipboardLabel, report.text, screenshot)
        return settle(report, copied, launched) { report.sink?.clearPreviousRun(it) }
    }

    /**
     * The rule the two routes feed: what the caller is told, and whether the
     * prior run is consumed.
     *
     * Separated from [deliver] so it is reachable without a `Context`. This
     * module's tests run on a plain JVM with no Robolectric, and this — not the
     * dozen lines of framework calls above — is the part that was worth sharing
     * between the apps and the part a mistake would be silent in.
     */
    internal fun settle(
        report: CollectedReport,
        copied: Boolean,
        launched: Boolean,
        clear: (PreviousRun) -> Unit,
    ): ShareOutcome {
        // Only once the report is retained where the user can still reach it.
        // If the copy failed, the log stays for the next attempt rather than
        // being spent on a report that reached nobody.
        if (copied) report.run?.let(clear)
        return when {
            launched -> ShareOutcome.SHARED
            copied -> ShareOutcome.COPIED_ONLY
            else -> ShareOutcome.FAILED
        }
    }

    /**
     * What the share intent carries, decided by the one fact that changes it:
     * whether a screenshot is attached. A screenshot flips the type off
     * `text/plain` and needs a read grant for the chosen target to open the URI;
     * a text-only report needs neither.
     *
     * Separated from [startChooser] for the reason [settle] is separated from
     * [deliver] — it is reachable without a `Context`, so the rule a mistake
     * would be silent in (attach the stream but forget the grant, and the target
     * receives a `content://` URI it cannot read) is covered on a plain JVM.
     */
    internal class ShareContent(val mimeType: String, val grantRead: Boolean)

    internal fun shareContentFor(hasScreenshot: Boolean): ShareContent =
        if (hasScreenshot) {
            ShareContent(mimeType = "image/png", grantRead = true)
        } else {
            ShareContent(mimeType = "text/plain", grantRead = false)
        }

    private fun copyToClipboard(
        context: Context,
        log: DebugLog,
        label: String,
        text: String,
    ): Boolean =
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (clipboard == null) {
                log.warning("No clipboard service, so the report was not copied")
                false
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                true
            }
        }.getOrElse {
            log.failure(it, "The report could not be copied to the clipboard")
            false
        }

    private fun startChooser(
        context: Context,
        log: DebugLog,
        subject: String,
        chooserTitle: String,
        clipLabel: String,
        text: String,
        screenshot: Uri?,
    ): Boolean =
        runCatching {
            val content = shareContentFor(hasScreenshot = screenshot != null)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = content.mimeType
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
                if (screenshot != null) {
                    putExtra(Intent.EXTRA_STREAM, screenshot)
                    // A ClipData beside EXTRA_STREAM so the read grant reaches
                    // whichever target the chooser resolves to, not only the one
                    // the extra names -- some Android versions carry the grant on
                    // the ClipData rather than the stream extra.
                    clipData = ClipData.newRawUri(clipLabel, screenshot)
                    // The grant flag goes on `send` here, *before* the chooser is
                    // built: createChooser copies the target's ClipData and grant
                    // flags onto the chooser at construction, so a flag added
                    // after would leave the chooser holding a ClipData it has no
                    // permission to read (Codex, PR #46).
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val chooser = Intent.createChooser(send, chooserTitle)
                // Some callers hand in a non-Activity context.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (content.grantRead) {
                // Also on the chooser itself, belt-and-suspenders: the target may
                // be handed either intent, and the grant only works on the one it
                // gets.
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        }.getOrElse {
            log.failure(it, "The share chooser could not be opened")
            false
        }
}
