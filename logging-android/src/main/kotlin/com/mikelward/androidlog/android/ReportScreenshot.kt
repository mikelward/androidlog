package com.mikelward.androidlog.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import com.mikelward.androidlog.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the current screen as a PNG for a bug report — the shared, hardened
 * half of the capture that every app was hand-rolling the same way.
 *
 * **What stays with the app is the [FileProvider] URI.** This returns the PNG
 * as a plain [File]; the consuming app turns it into a shareable `content://`
 * URI through *its own* `FileProvider` — the authority and paths only it can
 * declare — and hands that URI to [DebugReport.deliver]. Minting it here would
 * force a provider, a manifest entry and a resource on every consumer for a
 * picture only some of them send, which is the same reason `deliver` already
 * takes the URI rather than owning the provider (see its KDoc). So this module
 * keeps its no-resources, no-`androidx` promise: the returned file is the whole
 * of the contract.
 *
 * **The window alone.** [capture] shoots `activity.window`, so a dialog over it
 * — a Compose `AlertDialog` is a separate window — and its dim scrim are not in
 * the shot. What is captured is the screen the user is reporting.
 *
 * **Never a dropped share.** A finished window, a failed `PixelCopy`, or a
 * persist error yields `null`, which the caller turns into a text-only report
 * rather than nothing. Every failure is logged through [DebugLog] with
 * sanitized context — a class name or a fixed `PixelCopy` code, never a
 * coordinate — rather than swallowed.
 */
object ReportScreenshot {

    /**
     * How long a capture survives before a later capture's prune may delete it,
     * unless the caller overrides it. Eviction is by age, not count: a
     * count-based "keep newest N" can delete a capture that is still in flight —
     * several reports started close together each capture before their delivery
     * runs — whereas anything younger than this window is always safe. A day is
     * deliberately generous: the app can't know when a share target is done
     * reading a granted URI, so the window outlasts any realistic hold (an open
     * email/message compose), which a day does. `cacheDir`'s own OS eviction
     * under storage pressure is the backstop past that.
     */
    const val DEFAULT_RETAIN_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Bounds the wait on a `PixelCopy` that never calls back (see [capture]). */
    private const val COPY_TIMEOUT_MILLIS: Long = 10_000

    /** `screenshot-<capturedAtMillis>-<rand>.png`; the millis is read by the prune. */
    private const val CAPTURE_PREFIX = "screenshot-"
    private const val CAPTURE_SUFFIX = ".png"

    /**
     * Captures [activity]'s window to a PNG in [dir] and returns the file, or
     * `null` if there is nothing worth capturing (a finished window) or a step
     * failed. **Blocks** on the copy — call it off the main thread; it posts the
     * window read to the main thread, issues the copy with its result delivered
     * on a private thread, and waits. Called on the main thread it still returns
     * a picture (the window read runs inline, the copy's callback lands off it,
     * so nothing deadlocks) but it blocks the main thread for the length of the
     * copy — which is why the contract is to call it off the main thread.
     *
     * Old captures in [dir] past [retainMillis] are pruned first; the returned
     * file's name carries its capture millis so the next prune can age it. The
     * caller owns [dir] (typically `cacheDir/<something>`) and scopes its
     * `FileProvider` to it.
     */
    fun capture(
        activity: Activity,
        dir: File,
        log: DebugLog,
        retainMillis: Long = DEFAULT_RETAIN_MILLIS,
    ): File? {
        // Logged, not silently dropped: a throw while reading the window or
        // allocating the buffer would otherwise become a text-only report with
        // nothing saying why.
        val bitmap = try {
            captureWindow(activity, log)
        } catch (e: Exception) {
            log.failure(e, "bug report: window capture failed: %s", e.javaClass.simpleName)
            null
        } ?: return null

        // Only the hand-off returns the file; every other exit (a false encode,
        // a throw mid-encode) leaves an unusable PNG, so one `finally` deletes
        // it. The full-window ARGB_8888 buffer (10-30 MB on current phones) is
        // freed the same way, once the PNG on disk no longer needs it.
        var file: File? = null
        var handedOff = false
        return try {
            dir.mkdirs()
            // Prune only captures older than the window: a recent one may be a
            // concurrent report's in-flight capture, or a URI a share target
            // still reads lazily. Age, not count.
            prunePersistedScreenshots(dir, System.currentTimeMillis(), retainMillis, log)
            // createTempFile's random suffix keeps two reports persisting in the
            // same millisecond from colliding on one path; the capture millis
            // stays in the prefix so the age prune still reads it.
            file = File.createTempFile("$CAPTURE_PREFIX${System.currentTimeMillis()}-", CAPTURE_SUFFIX, dir)
            val compressed = FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!compressed) {
                // A false return (no throw) leaves a truncated/empty PNG; handing
                // its file back would attach a broken screenshot rather than the
                // promised text-only fallback.
                log.warning("bug report: screenshot PNG encode returned false")
                null
            } else {
                handedOff = true
                file
            }
        } catch (e: Exception) {
            log.failure(e, "bug report: screenshot persist failed: %s", e.javaClass.simpleName)
            null
        } finally {
            if (!handedOff) file?.let { deleteOrLog(it, log) }
            bitmap.recycle()
        }
    }

    /**
     * Deletes `screenshot-*.png` captures in [dir] older than [retainMillis]
     * before [now], age judged by the millis embedded in the filename (falling
     * back to `lastModified` for a name that doesn't parse). Age rather than
     * count so a capture still in flight — younger than the window — is never
     * pruned. Visible for tests.
     */
    internal fun prunePersistedScreenshots(dir: File, now: Long, retainMillis: Long, log: DebugLog) {
        if (retainMillis < 0) {
            // A negative window makes every non-future capture "expired", so the
            // prune would evict even an in-flight one — the race the age policy
            // exists to prevent. An invalid window is a caller bug, so skip the
            // prune (delete nothing) and report it rather than delete everything.
            log.warning("bug report: negative screenshot retention (%s ms); skipping the prune", retainMillis)
            return
        }
        val captures = dir.listFiles { file ->
            file.isFile && file.name.startsWith(CAPTURE_PREFIX) && file.name.endsWith(CAPTURE_SUFFIX)
        }
        if (captures == null) {
            // null is a scan failure (an unreadable directory), not an empty one;
            // report it rather than silently skip the prune and let expired
            // captures accumulate unnoticed.
            log.warning("bug report: could not scan the screenshot cache to prune it")
            return
        }
        captures.forEach { file ->
            // The name carries the capture millis; fall back to the file's mtime,
            // but only when it is readable. lastModified() returns 0 for both a
            // failed read and a file dated at the epoch, so a 0 is an unknown age:
            // leave the file for a later prune rather than delete it as ancient.
            val capturedAt = file.name.removePrefix(CAPTURE_PREFIX).substringBefore("-").toLongOrNull()
                ?: file.lastModified().takeIf { it > 0L }
            if (capturedAt == null) {
                log.warning("bug report: could not determine a screenshot's age; leaving it: %s", file.name)
                return@forEach
            }
            if (now - capturedAt > retainMillis) deleteOrLog(file, log)
        }
    }

    /**
     * Deletes [file], reporting a genuine failure rather than swallowing it.
     * `delete()` returns `false` both when the file resists deletion and when it
     * is already gone (a concurrent capture's prune raced this one) — only the
     * first is a failure, so the still-`exists()` check tells them apart and a
     * lost race stays quiet. The name is synthetic (`screenshot-<millis>-<rand>`),
     * not user data.
     */
    private fun deleteOrLog(file: File, log: DebugLog) {
        if (!file.delete() && file.exists()) {
            log.warning("bug report: could not delete a screenshot file: %s", file.name)
        }
    }

    /**
     * Reads the window geometry on the main thread, allocates the buffer off it,
     * copies into the buffer via `PixelCopy`, and returns the filled bitmap (or
     * `null` if there is no drawable window or the copy failed). The allocation
     * and zero-fill of a 10-30 MB ARGB_8888 buffer happen after the one
     * main-thread hop (the geometry read), off it, so they don't stall the frame.
     */
    private fun captureWindow(activity: Activity, log: DebugLog): Bitmap? {
        // Window token and view geometry are main-thread state; read them there.
        val geometry = onMainThread {
            if (activity.isFinishing || activity.isDestroyed) return@onMainThread null
            val window = activity.window ?: return@onMainThread null
            val view = window.decorView
            if (view.width <= 0 || view.height <= 0) return@onMainThread null
            val location = IntArray(2)
            view.getLocationInWindow(location)
            WindowGeometry(
                window = window,
                width = view.width,
                height = view.height,
                rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
            )
        } ?: return null

        // Off the main thread (capture() runs there): the heavy allocation.
        val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
        // On failure the bitmap is dropped for GC rather than recycled here: a
        // copy that timed out (below) may not have called back yet, and PixelCopy
        // writing into a recycled buffer would crash. The one bitmap that IS
        // returned is recycled by capture() once its PNG is written.
        return if (requestPixelCopy(geometry.window, geometry.rect, bitmap, log)) bitmap else null
    }

    private class WindowGeometry(
        val window: Window,
        val width: Int,
        val height: Int,
        val rect: Rect,
    )

    /**
     * Runs [block] on the main thread and returns its result, blocking the
     * caller until it completes. Runs inline when already on the main thread, so
     * a caller that ignored the off-thread contract doesn't deadlock on a post
     * to a looper it is itself blocking.
     */
    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching { block() })
            latch.countDown()
        }
        latch.await()
        // Rethrows on the caller's thread, where capture()'s catch handles it.
        return result.get().getOrThrow()
    }

    /**
     * Issues a `PixelCopy` of [rect] from [window] into [bitmap], its result
     * delivered on a private thread, and blocks until that callback reports
     * whether the copy succeeded. An ordinary failure (e.g. `ERROR_TIMEOUT`) is
     * logged with its code so a report that arrives text-only still says why; a
     * request that never calls back is bounded by [COPY_TIMEOUT_MILLIS] rather
     * than hanging the caller forever.
     */
    private fun requestPixelCopy(window: Window, rect: Rect, bitmap: Bitmap, log: DebugLog): Boolean {
        val latch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        // The result is delivered on a private thread, not the caller's. A main
        // looper handler could not run the callback while a main-thread caller
        // (against the off-thread contract) is blocked in await() below — the two
        // would deadlock until the timeout. `PixelCopy.request` may be issued
        // from any thread, so it is not posted to the main thread either.
        val callbackThread = HandlerThread("androidlog-screenshot").apply { start() }
        try {
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    // The code is a fixed PixelCopy constant, not user data.
                    log.warning("bug report: PixelCopy failed (code %s)", result)
                }
                succeeded.set(result == PixelCopy.SUCCESS)
                latch.countDown()
            }, Handler(callbackThread.looper))
            return if (latch.await(COPY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                succeeded.get()
            } else {
                log.warning("bug report: PixelCopy did not report within %s ms", COPY_TIMEOUT_MILLIS)
                false
            }
        } catch (e: Exception) {
            // A failed request degrades to a text-only report; a fatal Error (an
            // allocation or native-linkage failure) is not ours to turn into one,
            // so it is not caught here.
            log.failure(e, "bug report: PixelCopy.request threw: %s", e.javaClass.simpleName)
            return false
        } finally {
            // A late result after a timeout has nowhere to go and isn't needed;
            // the bitmap is dropped for GC (never recycled on failure) so a
            // still-running copy can't write into a freed buffer.
            callbackThread.quitSafely()
        }
    }
}
