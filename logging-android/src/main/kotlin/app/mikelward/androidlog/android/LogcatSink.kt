package app.mikelward.androidlog.android

import android.util.Log
import app.mikelward.androidlog.DebugLog

/** Comfortably inside logcat's ~4 KB per-message ceiling, measured in modified UTF-8 bytes. */
private const val MAX_CHUNK_BYTES = 3_000

/**
 * Mirrors each entry to logcat, for reading a running build over adb.
 *
 * Everything goes out at `DEBUG` rather than being mapped back to a logcat
 * level: the entry's own level character is already in the line, and recovering
 * it would mean parsing the rendered string back apart — a second, weaker copy
 * of a decision `DebugLog` has already made. `adb logcat` filters on the tag,
 * which is what anyone reading this is filtering on anyway.
 *
 * Long entries are split across calls because logcat drops a message past
 * roughly 4 KB, and a silently-truncated stack trace is worse than two lines.
 * The split is by line first, so a trace stays readable.
 *
 * Nothing here is a privacy decision. Logcat is on-device and the entry arrives
 * already rendered under the floor in `LogValue.kt`; this writes what it is
 * given.
 */
class LogcatSink(private val tag: String) : DebugLog.Sink {

    override fun log(line: String) {
        for (part in line.split('\n')) {
            for (chunk in part.chunkedByModifiedUtf8Bytes(MAX_CHUNK_BYTES)) {
                Log.d(tag, chunk)
            }
        }
    }
}

/**
 * Splits into pieces of at most [maxBytes] **modified UTF-8 bytes**, never
 * cutting a code point in half.
 *
 * Bytes, not `length`, because logcat's ceiling is a payload size while
 * `String.length` counts UTF-16 code units. A line of CJK text encodes at three
 * bytes per unit, so an entry well under the character budget can be over the
 * byte one — and logcat's response to that is to drop the tail, taking the
 * stack frames with it, silently (Codex, PR #1).
 *
 * *Modified* UTF-8, which is what a `String` becomes crossing JNI on its way to
 * the native logger: each half of a surrogate pair is encoded separately, so a
 * supplementary character costs six bytes there rather than the four standard
 * UTF-8 would spend. Counted the standard way, a line of emoji measured a third
 * under what the logger actually received and could still lose its tail. This
 * is the safe direction to be wrong in regardless — modified UTF-8 is never
 * shorter than standard, so a chunk sized this way fits either encoding.
 *
 * Internal rather than private so a JVM unit test can reach it: the sink itself
 * needs `android.util.Log`, and this is the part with the arithmetic in it.
 */
internal fun String.chunkedByModifiedUtf8Bytes(maxBytes: Int): List<String> {
    // Modified UTF-8 spends at most three bytes per UTF-16 code unit -- exactly
    // three for a surrogate, which is what makes a pair six. So this is a true
    // upper bound, and under it no measuring is needed at all, which is every
    // ordinary line.
    if (length <= maxBytes / 3) return listOf(this)

    val chunks = mutableListOf<String>()
    val chunk = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val units = Character.charCount(codePoint)
        val size = modifiedUtf8Size(codePoint)
        if (bytes + size > maxBytes && chunk.isNotEmpty()) {
            chunks += chunk.toString()
            chunk.setLength(0)
            bytes = 0
        }
        chunk.append(this, index, index + units)
        bytes += size
        index += units
    }
    if (chunk.isNotEmpty()) chunks += chunk.toString()
    return chunks
}

/**
 * One code point's size in modified UTF-8 — the encoding JNI hands the native
 * logger, not the standard one.
 *
 * Two differences from standard UTF-8, both making a string longer, never
 * shorter: `NUL` takes a two-byte form so the payload can stay
 * null-terminated, and a supplementary character is encoded as its two
 * surrogates at three bytes each rather than as one four-byte sequence.
 */
private fun modifiedUtf8Size(codePoint: Int): Int = when {
    codePoint == 0 -> 2
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 6
}
