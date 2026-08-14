/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "biz.ganttproject.mobile"
  compileSdk = 35

  defaultConfig {
    applicationId = "biz.ganttproject.mobile"
    // API 26 gives java.time without desugaring and covers the overwhelming
    // majority of devices still receiving updates.
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      // Left unminified for now: the app has no size pressure and an
      // unobfuscated release build makes crash reports from users readable.
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }

  buildFeatures {
    compose = true
    // Needed for BuildConfig.VERSION_NAME, shown in the About dialog.
    // AGP 8 turns this off by default.
    buildConfig = true
  }

  packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
  }
}

dependencies {
  // All file-format, scheduling and matching logic lives here and is tested
  // on a plain JVM, without an emulator.
  implementation(project(":gantt-core"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // Supplies collectAsStateWithLifecycle; it is a separate artifact from
  // lifecycle-runtime-ktx and is easy to miss.
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.extended)
  // Home-screen widget. Glance is Compose for widgets; the alternative,
  // RemoteViews, would mean a second UI toolkit in the same app.
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)

  debugImplementation(libs.androidx.ui.tooling)
}
