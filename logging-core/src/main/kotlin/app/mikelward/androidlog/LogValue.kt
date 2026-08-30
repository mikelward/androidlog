package app.mikelward.androidlog

/**
 * Decides, per argument, what a line of the debug log may carry off the device.
 *
 * The rule is **default-safe**: a log call is a hard-coded format string plus
 * arguments, and an argument may leave only if its *type* says it cannot name
 * anything of the user's. The format string is a source literal, so it is safe
 * by construction. Everything an app knows about a person — a phone number, a
 * contact, an SSID, a package name, a place name they typed — arrives as a
 * [String], so every [String] argument is withheld unless the call site says
 * otherwise.
 *
 * The consumers arrived at this after a filter that worked the other way round:
 * that one matched tokens against values it had already seen and redacted the
 * hits, so it was correct only for the categories it had been taught. Every
 * review round found another it did not know about, which is the shape of a
 * rule that fails *open*. Inverting the default retires the class — a call site
 * added next year is safe without anyone remembering to teach a filter about
 * it.
 *
 * **There is no scrubber here, and that is the design** (maintainer,
 * 2026-08-29). Scrubbing is a last-resort net over already-rendered text, and
 * what it has to catch differs per app — a phone number is simmo's problem, an
 * SSID snoozemo's, a package name Type Launcher's. An app that wants one wraps
 * its own sink around this core, where a mistake in it cannot weaken the type
 * rule above.
 *
 * Nothing is derived off-device here today: every sink the consumers register
 * is on-device. This exists so that adding an off-device mirror later is a
 * change that cannot quietly widen what is sent. The on-device log is
 * unaffected and always renders every argument in full — it is what the user
 * reviews and consents to before sharing a report, and the values in it are
 * what make a bug reproducible.
 */

/** Rendered in place of an argument that may not leave the device. */
const val REDACTED_PLACEHOLDER = "•••"

/**
 * Marks a value that may be carried in full even though its type would withhold
 * it — fixed vocabulary that names the platform rather than anything of the
 * user's (a state name, an intent action, an end reason, a wake-up source, a
 * URI reduced to its scheme).
 */
@JvmInline
value class SafeLogValue(val value: Any?)

/**
 * Marks a value that must be withheld even though its type would allow it — a
 * coordinate as a [Double], say, or an identifying number, where the type rule
 * alone would let it through.
 */
@JvmInline
value class SensitiveLogValue(val value: Any?)

/**
 * A value with two renderings: the full one for the on-device log, and a
 * reduced one for anywhere else.
 *
 * This exists for the composite summary — a snapshot of app state whose *shape*
 * is exactly what a report is read for, while some of its fields name the user.
 * As a plain [String] the whole summary would be withheld, and a failure nobody
 * can diagnose is its own kind of loss.
 */
class LogSummary(
    /** Rendered in the on-device log. */
    val full: String,
    /** Rendered off device, with the identifying fields removed. */
    val mirrored: String,
) {
    override fun toString(): String = full
}

/** See [SafeLogValue]. */
fun safe(value: Any?): SafeLogValue = SafeLogValue(value)

/** See [SensitiveLogValue]. */
fun sensitive(value: Any?): SensitiveLogValue = SensitiveLogValue(value)

/**
 * Whether [argument] may appear in full off the device.
 *
 * Numbers pass by default: a count, a duration, a distance in meters, a fix
 * accuracy. Those say whether a mechanism worked, which is the diagnostic.
 * Where a number genuinely does identify someone — a coordinate above all — the
 * call site wraps it in [sensitive]; the default is not the verdict.
 *
 * [String] does not pass, and if you read one rule here it is that one: it is
 * the type every identifier in every consumer arrives as.
 */
fun logArgumentMayLeaveDevice(argument: Any?): Boolean {
    val untagged = untag(argument)
    // Any `sensitive` in the stack withholds, whatever is wrapped around it.
    if (untagged.sensitive) return false
    if (untagged.tagged) return true
    return mayLeaveDeviceUntagged(untagged.value)
}

private fun mayLeaveDeviceUntagged(argument: Any?): Boolean = when (argument) {
    // Carries both renderings and picks between them itself.
    is LogSummary -> true
    null -> true
    is Boolean -> true
    is Char -> true
    is Byte -> true
    is Short -> true
    is Int -> true
    is Long -> true
    is Float -> true
    is Double -> true
    is Enum<*> -> true
    else -> false
}

/** How deep a nested composite is followed before it renders as its type. */
private const val MAX_RENDER_DEPTH = 3

/** How many elements of one composite are rendered before the rest are counted. */
private const val MAX_RENDERED_ELEMENTS = 20

/**
 * Renders one value — the single rendering path, tagged or not.
 *
 * **The library renders only what it defines the rendering of, and never calls
 * an unknown `toString()`.** That is the whole rule, and it is the one the
 * no-messages floor needs: a `toString()` this code did not write can reach a
 * throwable's message transitively, and four review rounds each found a new
 * route by which it did (Codex, PR #1) — a bare throwable, `safe(throwable)`,
 * `safe(safe(throwable))`, and a throwable inside a list. Special-casing each
 * route left the mechanism standing; refusing the unknown `toString()` retires
 * it (maintainer, 2026-08-30).
 *
 * So the branches below are not a list of things that leak. They are the types
 * whose rendering is fixed by the language or decided here:
 *
 * - Numbers, `Boolean`, `Char` and `String` render as themselves. `String` is
 *   on the list because it costs nothing to put there — it holds no other
 *   object, so there is nothing for a recursive `toString()` to reach. A
 *   *call site* that builds `"failed: " + e.message` itself is beyond any
 *   mechanism here; the type rule withholds every `String` off device and the
 *   rest is review.
 * - An enum renders its **constant name**, not `toString()`, which is
 *   overridable and could read live state at call time.
 * - A throwable renders its **type alone**. Type rather than type-and-frames
 *   because this renders *inside* a line; `DebugLog.failure` is how a throwable
 *   gets its frames.
 * - A collection, map or array renders its **elements as arguments in their
 *   own right**, back through [renderArgument], which is what pays for the
 *   rule above: `listOf("a", "b")` still reads `[a, b]`, while `listOf(e)`
 *   reads `[java.lang.IllegalStateException]` and `listOf(sensitive(51.5))`
 *   reads `[51.5]` on device.
 * - Anything else renders as its class name. A domain object is exactly the
 *   case where this code cannot know what is inside, so it does not guess —
 *   [LogSummary] is how a call site renders one deliberately.
 *
 * [depth] bounds the recursion, so a self-referential collection renders its
 * type instead of overflowing the stack — recording must not throw.
 */
private fun renderPlain(value: Any?, redactSensitive: Boolean, depth: Int): String = when {
    value == null -> "null"
    value is String -> value
    value is Boolean || value is Char ||
        value is Byte || value is Short || value is Int || value is Long ||
        value is Float || value is Double -> value.toString()
    value is Enum<*> -> value.name
    value is Throwable -> value.javaClass.name
    depth >= MAX_RENDER_DEPTH -> value.javaClass.name
    value is Collection<*> -> renderElements(value.asSequence(), value.size) {
        renderArgument(it, redactSensitive, depth + 1)
    }
    value is Array<*> -> renderElements(value.asSequence(), value.size) {
        renderArgument(it, redactSensitive, depth + 1)
    }
    value is Map<*, *> -> renderElements(
        value.entries.asSequence(),
        value.size,
        open = "{",
        close = "}",
    ) {
        val entry = it as Map.Entry<*, *>
        renderArgument(entry.key, redactSensitive, depth + 1) + "=" +
            renderArgument(entry.value, redactSensitive, depth + 1)
    }
    else -> value.javaClass.name
}

/**
 * Joins a bounded prefix of [values], noting how many were left out.
 *
 * Bounded because this runs on the recording path: an entry over
 * `maxEntryChars` is truncated afterward either way, but building a million
 * elements first is work nobody asked for.
 *
 * A [Sequence] and a [render] *parameter*, so the bound is applied before an
 * element is rendered rather than after. Mapping a map's entry set eagerly
 * rendered a million-entry map in full and then threw all but twenty away —
 * the bound was there, and the cost it exists to avoid was paid in full, on
 * the one path that cannot afford it (Codex, PR #3). The types now make that
 * shape unavailable: nothing is rendered until [take] has already cut the
 * sequence down.
 */
private fun renderElements(
    values: Sequence<Any?>,
    size: Int,
    open: String = "[",
    close: String = "]",
    render: (Any?) -> String,
): String {
    val rendered = values.take(MAX_RENDERED_ELEMENTS).joinToString(", ", transform = render)
    val omitted = size - MAX_RENDERED_ELEMENTS
    return if (omitted > 0) "$open$rendered, …$omitted more$close" else "$open$rendered$close"
}

/**
 * One argument stripped of however many tags were wrapped around it.
 *
 * Nesting is a call-site slip rather than an idiom -- but an unhandled slip
 * defeated the floor twice over, because a tag's *generated* `toString()`
 * prints its contents: `safe(safe(exception))` reached the untagged branch,
 * rendered the outer wrapper through `toString()`, and put the exception
 * message it prints straight into the line the no-messages rule exists to keep
 * it out of. `safe(sensitive(x))` read as safe on the strength of the outer tag
 * alone, and `safe(summary)` rendered a [LogSummary] whole rather than through
 * its own mirrored form (Codex, PR #1).
 *
 * So the tags are collapsed before anything is decided, and **`sensitive`
 * dominates**: a value the call site marked as the user's stays withheld
 * however many `safe` wrappers were put around it. That is the direction a slip
 * should fail in.
 *
 * The walk terminates by construction -- each wrapper has to be built around an
 * already-built value, so no chain of them can be cyclic.
 */
private class Untagged(val value: Any?, val tagged: Boolean, val sensitive: Boolean)

private fun untag(argument: Any?): Untagged {
    var value = argument
    var tagged = false
    var sensitive = false
    while (true) {
        when (value) {
            is SafeLogValue -> {
                tagged = true
                value = value.value
            }
            is SensitiveLogValue -> {
                tagged = true
                sensitive = true
                value = value.value
            }
            else -> return Untagged(value, tagged, sensitive)
        }
    }
}

/**
 * The value under however many tags were wrapped around [argument] — for a
 * caller that has to look at what an argument *is* rather than render it.
 *
 * [DebugLog.warning] is the one such caller: it reroutes a throwable to
 * [DebugLog.failure], and a throwable someone wrapped in [safe] is the same
 * misuse as a bare one (Codex, PR #1).
 */
internal fun untaggedLogValue(argument: Any?): Any? = untag(argument).value

/**
 * Renders one argument, or one element of a composite — they are the same
 * thing, and that is the point: **an element renders exactly as it would if it
 * were the argument itself.** Anything less was how a tag or a [LogSummary]
 * inside a list came out as the wrapper's class name, losing the value the
 * call site had gone out of its way to mark (Codex, PR #3).
 *
 * The floor is applied per *argument*, and a composite is one argument: an
 * untagged one is withheld off device whole, so its elements are never reached
 * there, and a [safe] one is the call site asserting the composite. What still
 * withholds inside it is an explicit [sensitive], because sensitive dominates
 * wherever it appears. Re-applying the whole floor to each element instead
 * would withhold the ordinary strings in a composite the call site had already
 * vouched for -- over-strict, and paid for in diagnostics.
 *
 * At [depth] 0 this is called from [formatLogMessage], which has already put
 * every withheld argument behind [REDACTED_PLACEHOLDER]; the sensitive check
 * below is then a no-op, since nothing sensitive is permitted to reach it.
 */
private fun renderArgument(argument: Any?, redactSensitive: Boolean, depth: Int = 0): String {
    val untagged = untag(argument)
    if (redactSensitive && untagged.sensitive) return REDACTED_PLACEHOLDER
    val value = untagged.value
    return when {
        // Ahead of the tag branch: a summary carries both renderings and picks
        // between them itself, and a tag wrapped around one must not take that
        // choice away from it.
        value is LogSummary -> if (redactSensitive) value.mirrored else value.full
        // Tagged or not, one rendering path. A tag answers "may this leave the
        // device?", which is [logArgumentMayLeaveDevice]'s question, and
        // nothing about how the value is written down (maintainer,
        // 2026-08-30). Letting `safe` also mean "print it via `toString()`"
        // made it a way around the no-messages rule -- `safe(x)` where `x`
        // holds a throwable rendered the message -- for no benefit the call
        // sites were using: `safe` carries fixed-vocabulary strings and state
        // names, and `String` rendering is the same on both paths.
        else -> renderPlain(value, redactSensitive, depth)
    }
}

/**
 * Substitutes [args] into [format], replacing each `%s` in order. `%%` renders a
 * literal `%`. When [redactSensitive] is set, any argument
 * [logArgumentMayLeaveDevice] withholds renders as [REDACTED_PLACEHOLDER]
 * instead — that is the only difference between the on-device rendering and the
 * mirrored one.
 *
 * Deliberately not `String.format`: this needs no locale (whose default would be
 * a live trap for `%d`), raises no `FormatException` from a stray `%` in a
 * message, and supports exactly the one placeholder the call sites use.
 *
 * A mismatch between placeholders and arguments is surfaced rather than
 * swallowed — a surplus `%s` is left in place and a surplus argument is
 * appended — so a wrong format string reads as obviously wrong in the log
 * instead of quietly dropping the value someone was trying to record. Surplus
 * arguments go through the same redaction as placed ones, so a mismatch can
 * never become a leak.
 */
fun formatLogMessage(
    format: String,
    args: Array<out Any?>,
    redactSensitive: Boolean,
): String {
    fun render(argument: Any?): String =
        if (redactSensitive && !logArgumentMayLeaveDevice(argument)) {
            REDACTED_PLACEHOLDER
        } else {
            renderArgument(argument, redactSensitive)
        }

    if (args.isEmpty() && '%' !in format) return format

    val out = StringBuilder(format.length + args.size * 8)
    var index = 0
    var next = 0
    while (index < format.length) {
        val char = format[index]
        if (char == '%' && index + 1 < format.length) {
            when (format[index + 1]) {
                's' -> {
                    if (next < args.size) out.append(render(args[next++])) else out.append("%s")
                    index += 2
                    continue
                }
                '%' -> {
                    out.append('%')
                    index += 2
                    continue
                }
            }
        }
        out.append(char)
        index++
    }
    while (next < args.size) {
        out.append(" [unplaced arg] ").append(render(args[next++]))
    }
    return out.toString()
}
