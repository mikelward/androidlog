plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Consumers resolve this build by coordinate through a composite build
// (`includeBuild`), so every module needs a group for the substitution to match
// on. The version is inert: substitution happens before resolution, so nothing
// ever looks it up remotely, and there is deliberately nothing to bump. See
// README.md "Consuming it".
allprojects {
    group = "app.mikelward.androidlog"
    version = "0.0"
}
