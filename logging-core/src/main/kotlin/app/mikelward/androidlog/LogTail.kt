package app.mikelward.androidlog


/** Marks a log line cut short, so a reader can tell it was clamped, not written that way. */
internal const val TRUNCATION_MARKER = "…(truncated)"

/**
 * The newest lines of [lines] (oldest-first) whose combined length fits
 * [budgetChars], returned oldest-first — so a report keeps the freshest context
 * inside the Binder limit a share has to fit through.
 *
 * A single newest line that alone exceeds the budget is kept **clamped to it**
 * rather than whole: returning nothing would drop the freshest context entirely,
 * but returning it whole would blow the very ceiling this exists to enforce.
 * Below the length of the marker itself there is no clamped form that fits, and
 * then nothing really is the answer.
 * Live entries are capped by [DebugLog]'s own per-entry bound so they cannot
 * reach that size, but a cache directory survives app upgrades — a prior-run
 * file written by a build from before that cap can hold an arbitrarily long
 * line, and it is read back unchanged.
 *
 * Here rather than beside the file sink that reads those prior-run files,
 * because it is a rule about log lines rather than about Android, and
 * [DebugLog] needs the same [TRUNCATION_MARKER] for its own trimming.
 *
 * [DebugLog.boundedSnapshot] deliberately does *not* call this. Its trim runs
 * over lines that are not yet strings and has to charge each one for the offset
 * anchor it will be given, so the two differ in more than their element type.
 */
fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline the join adds
        if (used + cost > budgetChars) {
            if (kept.isNotEmpty()) break
            val room = budgetChars - TRUNCATION_MARKER.length
            // A budget too small for the marker itself has no truthful clamped
            // form: emitting one overruns the ceiling the budget *is*, and the
            // clamp exists to enforce that ceiling. The same rule as
            // [DebugLog.boundedSnapshot]'s trim, kept in step deliberately —
            // one of these two drifting from the other is how the copies this
            // library replaced came to disagree.
            if (room < 0) break
            kept.addFirst(line.take(codePointCut(line, room)) + TRUNCATION_MARKER)
            break
        }
        kept.addFirst(line)
        used += cost
    }
    return kept
}

/**
 * Where to cut [text] to keep about [room] characters without splitting a pair.
 *
 * Stepped back off a high surrogate, so a cut never strands half a code point.
 * Shared by every place this library shortens a line — the entry-length clamp,
 * both trims — because a second cut counting UTF-16 units while the first
 * counted code points is exactly how two copies of a rule drift apart (Codex,
 * PR #7). [room] is clamped into range first, so a caller cannot index off
 * either end.
 */
internal fun codePointCut(text: String, room: Int): Int {
    val cut = room.coerceIn(0, text.length)
    return if (cut > 0 && text[cut - 1].isHighSurrogate()) cut - 1 else cut
}
