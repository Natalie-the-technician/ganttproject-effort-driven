/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.
Keeping the automatically created baselines from piling up — without ever touching a hand-named one.

This file is part of GanttProject, an opensource project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.sourceforge.ganttproject.fork

import net.sourceforge.ganttproject.GanttPreviousState
import java.text.MessageFormat
import java.util.Locale

/** At most this many AUTOMATICALLY created baselines per kind. Older ones of that kind go. */
const val MAX_AUTO_BASELINES_PER_KIND = 15

/**
 * The kinds of baseline this fork creates by itself, each with the message key its name is built
 * from.
 *
 * PER KIND AND NOT IN TOTAL, and that is the point of the enum. The levelling takes one before
 * every run; the supplement takes one whenever a task is added to the baselines. With a single
 * shared budget the kind that happens more often would eat the other one's places, and the states
 * one actually wanted to compare would be exactly the ones that disappear.
 *
 * ADDING A KIND MEANS ADDING IT HERE. A kind that is missing from this list is simply never
 * cleaned up — which is the safe direction: it piles up, it does not get deleted by mistake.
 */
enum class AutoBaselineKind(val nameKey: String) {
  /** `LevellingActions.kt`: the state before a levelling run, "Before levelling {0}". */
  BEFORE_LEVELLING("fork.baseline.name"),

  /** [BaselineCatchUp]: the supplement that takes the missing tasks in, with a timestamp. */
  CATCH_UP("fork.baseline.catchup.name")
}

/**
 * The languages whose patterns are recognised.
 *
 * A baseline name is built from a TRANSLATED pattern, so a project worked on in a German session
 * and tidied up in an English one would otherwise never be cleaned. Both bundle files this fork
 * carries are asked, plus whatever the current language is.
 *
 * IF A THIRD LANGUAGE FILE IS EVER ADDED, IT BELONGS HERE. What is missing from this list is never
 * recognised and therefore never deleted — the harmless direction, and the one this whole file is
 * built to stay on.
 */
private fun patternLocales(): Set<Locale> =
  linkedSetOf(Locale.getDefault(), Locale.ENGLISH, Locale.GERMAN)

/**
 * The pattern turned into an expression that matches the names it produces.
 *
 * BUILT THE SAME WAY THE NAME IS. The name comes out of `MessageFormat.format(pattern, stamp)`, so
 * the pattern is formatted here too — with a marker instead of the stamp — and only then split.
 * Reading the raw pattern and replacing `{0}` textually would get MessageFormat's own quoting
 * rules wrong (a single apostrophe in a translation escapes the next placeholder), and the
 * mismatch would show up as "the tidy-up silently stopped working in German".
 *
 * `.+` and not `.*`: a timestamp is never empty, so the bare fixed part alone does not match.
 */
private fun patternRegex(pattern: String): Regex {
  val marker = "@@AUTO-BASELINE-STAMP@@"
  val parts = MessageFormat.format(pattern, marker).split(marker)
  return Regex(parts.joinToString(".+") { Regex.escape(it) })
}

/**
 * Which automatic kind [name] belongs to, or null if it belongs to none.
 *
 * NULL IS THE ANSWER FOR EVERY HAND-GIVEN NAME, and that is the assurance this package rests on:
 * nothing without a kind is ever removed. The match is against the WHOLE name, so "Before levelling
 * the office move" — a perfectly ordinary thing to type — is not the automatic kind and stays.
 */
fun autoBaselineKindOf(name: String): AutoBaselineKind? {
  if (name.isEmpty()) {
    return null
  }
  AutoBaselineKind.entries.forEach { kind ->
    patternLocales().forEach { locale ->
      val pattern = ForkI18n.textOrNull(kind.nameKey, locale)
      if (pattern != null && patternRegex(pattern).matches(name)) {
        return kind
      }
    }
  }
  return null
}

/**
 * The baselines a tidy-up run would remove, oldest first, leaving at most [limit] per kind.
 *
 * @param incoming a kind that is about to gain one more baseline that does not exist yet. The
 *   caller asks the user BEFORE writing, and at that moment the new baseline is still to come —
 *   without counting it, the question would fail to mention the removal it is about to cause.
 *
 * "OLDEST" IS THE POSITION IN THE LIST. A `<previous-tasks>` element carries no timestamp, so
 * insertion order is the only ordering there is; it survives a save and a load because
 * `GanttXMLSaver` writes the list in order and `BaselineSerializer` reads it back in order. The
 * timestamp this fork puts in the NAME is for the person reading it, not for this sort — a name
 * can be edited, and sorting on something the user can rewrite would make deletion depend on
 * typing.
 */
fun autoBaselinesToPrune(baselines: List<GanttPreviousState>,
                         incoming: AutoBaselineKind? = null,
                         limit: Int = MAX_AUTO_BASELINES_PER_KIND): List<GanttPreviousState> {
  val doomed = mutableListOf<GanttPreviousState>()
  AutoBaselineKind.entries.forEach { kind ->
    val ofKind = baselines.filter { autoBaselineKindOf(it.name) == kind }
    val after = ofKind.size + (if (incoming == kind) 1 else 0)
    val surplus = after - limit
    if (surplus > 0) {
      doomed.addAll(ofKind.take(surplus))
    }
  }
  // In list order, so the caller's message reads the way the list does.
  return baselines.filter { doomed.contains(it) }
}

/**
 * Removes the surplus automatic baselines from [baselines] and returns those that went.
 *
 * ONLY WHAT MATCHES ONE OF THE PATTERNS. A baseline someone named by hand survives any number of
 * runs, however many of them there are — that is the promise of this package and it has a test of
 * its own (`AutoBaselinePruneTest`).
 *
 * `remove()` AS WELL AS TAKING IT OUT OF THE LIST: a baseline lives in a temporary file
 * (`GanttPreviousState.java:95-97`), and dropping only the list entry would leave the file behind
 * until the JVM exits — which is the very thing the limit exists to prevent.
 */
fun pruneAutoBaselines(baselines: MutableList<GanttPreviousState>,
                       limit: Int = MAX_AUTO_BASELINES_PER_KIND): List<GanttPreviousState> {
  val doomed = autoBaselinesToPrune(baselines, incoming = null, limit = limit)
  doomed.forEach {
    baselines.remove(it)
    it.remove()
  }
  return doomed
}
