/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A blue close to GanttProject's own task bars, so the app looks related to
// the desktop tool rather than like a generic Material sample.
private val GanttBlue = Color(0xFF1F3A5F)
private val GanttBlueLight = Color(0xFF8AB4F8)

private val LightScheme = lightColorScheme(
  primary = GanttBlue,
  secondary = Color(0xFF4A6785),
  tertiary = Color(0xFF7D5260)
)

private val DarkScheme = darkColorScheme(
  primary = GanttBlueLight,
  secondary = Color(0xFFA8C7E8),
  tertiary = Color(0xFFEFB8C8)
)

/** Colours a task bar by progress; used by the chart and the task list. */
object ChartColors {
  val bar = Color(0xFF4A90D9)
  val barDone = Color(0xFF3E9B5F)
  val barSummary = Color(0xFF37474F)
  val milestone = Color(0xFFD9534F)
  val overload = Color(0xFFD9534F)
  val weekend = Color(0x14000000)
  val today = Color(0xFFE57373)
}

@Composable
fun GanttMobileTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val context = LocalContext.current
  // Material You where the platform offers it, our own palette otherwise.
  val scheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    darkTheme -> DarkScheme
    else -> LightScheme
  }
  MaterialTheme(colorScheme = scheme, content = content)
}
