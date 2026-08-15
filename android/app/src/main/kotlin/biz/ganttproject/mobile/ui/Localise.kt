/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import biz.ganttproject.mobile.R
import biz.ganttproject.mobile.core.MatchReason
import biz.ganttproject.mobile.core.SplitValidation
import biz.ganttproject.mobile.core.TogglError
import biz.ganttproject.mobile.core.formatHours
import biz.ganttproject.mobile.data.FileError
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The single place where a typed result from the core becomes text a person
 * reads. Keeping every message here is what allows the core module to stay
 * free of Android and of any one language — and it means a translator only
 * ever has to touch the string resources, never Kotlin.
 */

@Composable
fun FileError.text(): String = when (this) {
  FileError.NotXml -> stringResource(R.string.error_not_xml)
  FileError.NotAProject -> stringResource(R.string.error_not_a_project)
  FileError.PermissionLost -> stringResource(R.string.error_permission_lost)
  is FileError.OpenFailed -> stringResource(R.string.error_open_failed)
  is FileError.SaveFailed -> stringResource(R.string.error_save_failed)
  FileError.ChangedElsewhere -> stringResource(R.string.error_changed_elsewhere)
  // Not a conflict: nothing is lost, the project is simply open on the
  // desktop. Saying so is the whole point of keeping it a separate case.
  FileError.LockedElsewhere -> stringResource(R.string.error_locked_elsewhere)
  FileError.UnknownVersion -> stringResource(R.string.error_unknown_version)
  is FileError.SyncFailed -> stringResource(R.string.error_sync_failed, detail)
  FileError.NoAccessToFolder -> stringResource(R.string.error_no_folder_access)
  FileError.GoneFromServer -> stringResource(R.string.error_gone_from_server)
  FileError.BadServerAddress -> stringResource(R.string.error_bad_server_address)
}

@Composable
fun TogglError.text(): String = when (this) {
  TogglError.InvalidToken -> stringResource(R.string.error_toggl_token)
  TogglError.Forbidden -> stringResource(R.string.error_toggl_forbidden)
  TogglError.RateLimited -> stringResource(R.string.error_toggl_rate)
  TogglError.InvalidDateRange -> stringResource(R.string.error_toggl_range)
  is TogglError.ServerError -> stringResource(R.string.error_toggl_server, status)
  is TogglError.Network -> stringResource(R.string.error_toggl_network, detail)
  is TogglError.Malformed -> stringResource(R.string.error_toggl_malformed)
  is TogglError.Unexpected -> stringResource(R.string.error_toggl_unexpected, status)
}

@Composable
fun SplitValidation.text(): String? = when (this) {
  SplitValidation.Valid -> null
  SplitValidation.NoParts -> stringResource(R.string.error_split_empty)
  is SplitValidation.NonPositivePart -> stringResource(R.string.error_split_nonpositive)
  is SplitValidation.DuplicateTask -> stringResource(R.string.error_split_duplicate)
  is SplitValidation.SumMismatch -> stringResource(
    R.string.error_split_sum, hours(actualHours), hours(expectedHours)
  )
}

@Composable
fun MatchReason.text(): String = when (this) {
  MatchReason.TASK_ID_REFERENCE -> stringResource(R.string.import_reason_id)
  MatchReason.LEARNED_KEY -> stringResource(R.string.import_reason_learned)
  MatchReason.EXACT_NAME -> stringResource(R.string.import_reason_name)
  MatchReason.NAME_CONTAINED -> stringResource(R.string.import_reason_contained)
  MatchReason.WORD_OVERLAP -> stringResource(R.string.import_reason_words)
}

/**
 * Hours in the reader's notation: a German locale expects "7,5", an English
 * one "7.5". The core stores and computes with a plain double throughout;
 * only the display differs.
 */
fun hours(value: Double?): String {
  val separator = java.text.DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
  return formatHours(value, separator)
}

/** Dates follow the device locale rather than the file's ISO format. */
fun formatDate(date: LocalDate): String =
  date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun formatDateRange(from: LocalDate, to: LocalDate): String =
  if (from == to) formatDate(from) else "${formatDate(from)} – ${formatDate(to)}"
