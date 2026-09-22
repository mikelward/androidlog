package com.mikelward.androidlog.android

import com.mikelward.androidlog.DebugLog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The capture's age-based prune, on a plain JVM — the part that decides which
 * files survive, which is where a mistake silently deletes a screenshot a share
 * target is still reading. The `PixelCopy` and bitmap paths need a device and
 * are exercised by a consuming app's Robolectric tests; this module stays
 * Robolectric-free (see [DebugReport]), so what is covered here is the file
 * logic that runs the same everywhere.
 *
 * Names carry the capture millis in the prefix, then the `-<rand>` suffix
 * `createTempFile` adds, so the prune reads the age from before the first dash.
 */
class ReportScreenshotTest {

    private val dir: File = Files.createTempDirectory("androidlog-screenshot").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val now = 10_000_000L
    private val retain = 5 * 60 * 1000L

    private fun log() = DebugLog(readMillis = { 0L })

    private fun capture(name: String): File = File(dir, name).apply { writeBytes(byteArrayOf(1)) }

    @Test
    fun `deletes captures older than the retain window and keeps recent ones`() {
        val old = capture("screenshot-${now - retain - 1}-r.png")
        val recent = capture("screenshot-${now - 5_000}-r.png")

        ReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain, log = log())

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `keeps a just-written in-flight capture`() {
        // Several reports started close together each capture before their own
        // delivery runs. A capture at age 0 is younger than the window, so a
        // concurrent report's prune must leave it — its own deliver still needs it.
        val inFlight = capture("screenshot-$now-r.png")

        ReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain, log = log())

        assertTrue(inFlight.exists())
    }

    @Test
    fun `ignores files that are not captures`() {
        val unrelated = capture("notes.txt")
        val old = capture("screenshot-${now - retain - 100}-r.png")

        ReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain, log = log())

        assertTrue(unrelated.exists())
        assertFalse(old.exists())
    }

    @Test
    fun `falls back to file mtime for a name without a parseable millis`() {
        // A name that doesn't carry a parseable capture millis ages by its mtime
        // rather than living forever (old) or being deleted regardless (recent).
        // Real wall clock, since setLastModified writes real-clock mtimes.
        val realNow = System.currentTimeMillis()
        val oldMtime = capture("screenshot-notanumber-r.png")
        oldMtime.setLastModified(realNow - retain - 60_000)
        val recentMtime = capture("screenshot-alsotext-r.png")
        recentMtime.setLastModified(realNow - 1_000)

        ReportScreenshot.prunePersistedScreenshots(dir, now = realNow, retainMillis = retain, log = log())

        assertFalse(oldMtime.exists())
        assertTrue(recentMtime.exists())
    }

    @Test
    fun `keeps a capture whose age cannot be determined`() {
        // No parseable millis in the name and an unreadable mtime (0): the age is
        // unknown, so the file is left for a later prune rather than deleted as if
        // it were epoch-old. (Where setLastModified(0) is refused, the file keeps
        // its recent creation mtime and is kept as recent — kept either way.)
        val unknownAge = capture("screenshot-notanumber-r.png")
        unknownAge.setLastModified(0L)

        ReportScreenshot.prunePersistedScreenshots(
            dir,
            now = System.currentTimeMillis(),
            retainMillis = retain,
            log = log(),
        )

        assertTrue(unknownAge.exists())
    }

    @Test
    fun `skips the prune on a negative retention window`() {
        // A negative window makes every capture "expired"; guarding it keeps an
        // invalid override from evicting even a capture that is still in flight.
        val old = capture("screenshot-${now - retain - 1}-r.png")

        ReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = -1L, log = log())

        assertTrue(old.exists())
    }
}
