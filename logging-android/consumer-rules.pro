# Rules every consuming app inherits. AGP merges these into the app's own R8
# configuration, so the four consumers get them without a per-app edit.

# A throwable is rendered into the log as its class name and nothing else --
# that is what makes it safe to carry at all, since the no-messages floor keeps
# its message out. Renamed by R8, that name reads `app.example.a.b` in a log the
# user pastes into an email, and the one thing a failure line exists to say is
# gone unless the reader also has the mapping file for that exact build.
#
# `-keepnames`, not `-keep`: this prevents *renaming* only. An exception class
# nothing references is still shrunk away, so the cost is the retained names of
# the exception types that actually ship.
#
# Platform and JDK exceptions were never affected -- R8 does not rename what it
# does not compile -- so this is about each app's own exception types, and the
# libraries' (Compose, coroutines, okhttp) that surface in a stack.
-keepnames class ** extends java.lang.Throwable
