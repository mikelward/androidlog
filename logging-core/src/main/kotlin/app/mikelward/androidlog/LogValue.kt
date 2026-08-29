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

/**
 * Renders one untagged value.
 *
 * A [Throwable] renders as its **type alone**, never `toString()`, because
 * `toString()` appends `getMessage()` and a platform exception quotes what it
 * was given — the number that was dialed, the network that was joined. The
 * no-messages rule is absolute, so it cannot hold only on the paths that
 * happen to route a throwable through `DebugLog.failure`: an exception handed
 * in as a formatting argument to `event`, or as a surplus argument to
 * `failure`, reaches this function instead and would otherwise carry its
 * message into the on-device buffer, which is the copy the user shares
 * (Codex, PR #1).
 *
 * `safe(throwable)` gets the same treatment. A call site can vouch for a value
 * being fixed vocabulary; it cannot vouch for what the platform put in an
 * exception message, so that opt-in does not reach this.
 *
 * The type alone rather than type-and-frames: this renders *inside* a line, and
 * a stack trace belongs under one. `DebugLog.failure` is how a throwable gets
 * its frames.
 */
private fun renderPlain(value: Any?): String = when (value) {
    is Throwable -> value.javaClass.name
    // The **constant name**, not `toString()`. An enum passes the type rule
    // above because its identity is fixed vocabulary — but `toString()` is
    // overridable, so an enum could hand back whatever an override chose to
    // read at call time and walk straight through a floor that let it past on
    // the strength of being an enum at all (Codex, PR #1). `name` is the thing
    // the rule actually vouched for, so `name` is what is rendered.
    is Enum<*> -> value.name
    else -> value.toString()
}

/**
 * Renders a value whose call site vouched for it with [safe] or marked it with
 * [sensitive].
 *
 * `toString()` is honored here, enums included: the call site asked for this
 * value specifically, which is exactly what the tags are for. The one thing a
 * tag cannot buy is a throwable's message — a call site can vouch for a value
 * being fixed vocabulary, but not for what the platform put in an exception.
 */
private fun renderTagged(value: Any?): String = when (value) {
    is Throwable -> value.javaClass.name
    else -> value.toString()
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

/** Unwraps any tags and renders the value the way the on-device log shows it. */
private fun renderLogArgument(argument: Any?, redactSensitive: Boolean): String {
    val untagged = untag(argument)
    val value = untagged.value
    return when {
        // Ahead of the tag branch: a summary carries both renderings and picks
        // between them itself, and a tag wrapped around one must not take that
        // choice away from it.
        value is LogSummary -> if (redactSensitive) value.mirrored else value.full
        untagged.tagged -> renderTagged(value)
        else -> renderPlain(value)
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
            renderLogArgument(argument, redactSensitive)
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
