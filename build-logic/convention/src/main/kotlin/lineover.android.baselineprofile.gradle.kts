// Applies the AndroidX Baseline Profile plugin (consumer in :app, producer in
// :baselineprofile). It lives on the build-logic classpath, so modules reach it
// through this convention plugin.
plugins {
    id("androidx.baselineprofile")
}
