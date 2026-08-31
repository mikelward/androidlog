package com.mikelward.androidlog.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
     */
    fun collect(
        log: DebugLog,
        sink: DebugFileSink?,
        heading: String = "--- earlier runs ---",
        buildPayload: () -> String,
    ): CollectedReport {
        val run = runCatching { sink?.readPreviousRun() }
            .onFailure { log.failure(it, "Earlier runs could not be read for a report") }
            .getOrNull()
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
        val text = if (run == null) own else "$own\n\n$heading\n${run.text}"
        return CollectedReport(text, run, sink)
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
     */
    fun deliver(
        context: Context,
        log: DebugLog,
        report: CollectedReport,
        subject: String,
        chooserTitle: String,
        clipboardLabel: String,
    ): ShareOutcome {
        val copied = copyToClipboard(context, log, clipboardLabel, report.text)
        val launched = startChooser(context, log, subject, chooserTitle, report.text)
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
        text: String,
    ): Boolean =
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(
                Intent.createChooser(send, chooserTitle)
                    // Some callers hand in a non-Activity context.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            log.failure(it, "The share chooser could not be opened")
            false
        }
}
