/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 *
 * Intentionally free of plugin declarations. Naming `com.android.application`
 * here — even with `apply false` — would make Gradle resolve the Android
 * plugin before anything is built, and :gantt-core could then no longer be
 * compiled without an Android SDK. Plugins are declared in the modules.
 */

tasks.register("checkCore") {
  group = "verification"
  description = "Builds and tests the core module. Runs without an Android SDK."
  dependsOn(":gantt-core:test")
}
