/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 *
 * A standalone Gradle build. It is deliberately NOT wired into the desktop
 * project's settings.gradle: the desktop build must keep working, unchanged,
 * on machines with no Android SDK.
 */

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "ganttproject-mobile"

// Pure Kotlin core: file format, scheduling arithmetic, time-entry matching.
// No Android dependency, so it builds and tests without an SDK.
include(":gantt-core")

// The UI is only included when an Android SDK is actually available.
//
// The point is not convenience. Everything that can silently corrupt a
// project file lives in :gantt-core, and it has to stay testable on machines
// without an SDK — including CI containers where the SDK download is blocked
// by network policy. Without this guard, Gradle would fail while merely
// configuring :app and take every core test down with it.
val androidSdkAvailable =
  System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
  include(":app")
} else {
  logger.lifecycle(
    "No Android SDK found - skipping the :app module. " +
      "The :gantt-core module still builds and tests."
  )
}
