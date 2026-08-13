/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 *
 * Pure Kotlin/JVM. NO Android dependency, on purpose: this module has to
 * build and test where no Android SDK is reachable. All file-format and
 * arithmetic logic therefore lives here rather than in :app.
 */
plugins {
  alias(libs.plugins.kotlin.jvm)
}

// No `jvmToolchain(...)`: that demands a JDK of exactly that version and
// breaks the build wherever a different one is installed. Only the target
// bytecode level is pinned - Java 17, matching what the Android plugin wants.
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}
