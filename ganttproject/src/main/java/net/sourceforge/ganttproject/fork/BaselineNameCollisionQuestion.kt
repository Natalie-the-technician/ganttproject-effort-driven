/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

THE FIFTH POLICY. [BaselineImport.kt] already carries out four ways of handling a baseline whose
name the target project already has (APPEND_AS_IS, PREFIX_WITH_PROJECT_NAME, REPLACE_EXISTING,
SKIP_COLLIDING) -- Natalie was offered all four, plus a fifth, "ask", on 08.10.2026 and chose
"ask" on 10.10.2026: "einmal fragen, für alle" -- ONE dialog that handles EVERY colliding name of
one import together, not one dialog per baseline.

WHY "ASK" IS NOT A FIFTH [BaselineNameCollision] VALUE. That enum is what [importBaselines] takes,
and its only parameter has NO default on purpose -- the caller has to name a decided value, which
is what stops a silent default from ever reaching it. Adding ASK there would not be a fifth
decided value, it would be an UNDECIDED one smuggled into a parameter that exists specifically to
reject exactly that. Asking belongs one layer above, where this file lives: it turns "ask" into
one of the three concrete values Natalie was actually offered to choose AMONG -- see
[BaselineCollisionAnswer] -- before anything calls [importBaselines] at all.

NOT ALL FOUR [BaselineNameCollision] VALUES ARE OFFERED IN THE DIALOG. SKIP_COLLIDING is not one
of the three choices Natalie named ("behalten / umbenennen / überschreiben"), so it is not a
button here. It still exists as a value for whatever OTHER caller of [importBaselines] wants it;
this file does not touch it.

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

/**
 * Names that [sourceBaselines] shares with [targetBaselines], each listed once.
 *
 * ONE ENTRY PER COLLIDING NAME, NOT PER BASELINE. Two source baselines of the same colliding name
 * -- the 15-auto-baselines-per-import case the task asks for a measured example of -- are one
 * entry here, because the dialog built on this list asks ONE question about that name, not two.
 * Order follows [sourceBaselines] so the dialog lists names in the order the import produced them,
 * not in whatever order a set would happen to iterate.
 *
 * EVALUATED AGAINST THE LISTS AS THEY ARE RIGHT NOW, the same choice [importBaselines] makes for
 * the same reason: two imported baselines can collide with each other, not only with the target's
 * own, and a snapshot taken before the import started could miss that.
 */
fun baselineNameCollisions(
  sourceBaselines: List<GanttPreviousState>,
  targetBaselines: List<GanttPreviousState>
): List<String> {
  val targetNames = targetBaselines.map { it.name }.toSet()
  return sourceBaselines.map { it.name }.filter { it in targetNames }.distinct()
}

/**
 * The three choices Natalie was asked to pick AMONG on 08.10.2026, each mapped to the
 * [BaselineNameCollision] value that already carries it out.
 */
enum class BaselineCollisionAnswer(val policy: BaselineNameCollision) {
  /**
   * "behalten" -- append the imported baseline under its unchanged name.
   *
   * CARRIES THE SAME PITFALL [BaselineNameCollision.APPEND_AS_IS] documents: the two identically
   * named baselines are indistinguishable in the list afterwards, and deleting one by name deletes
   * the OTHER one (`BaselineDialogAction.deleteValue` stops at the first match). The dialog text
   * has to say this, not only this source comment -- seeing it only here is the same mistake as
   * not saying it at all.
   */
  KEEP_BOTH(BaselineNameCollision.APPEND_AS_IS),

  /** "umbenennen" -- append the imported baseline under a name prefixed with its project's name. */
  RENAME_IMPORTED(BaselineNameCollision.PREFIX_WITH_PROJECT_NAME),

  /** "überschreiben" -- replace the target's baseline of that name. Loses the target's own state. */
  OVERWRITE_EXISTING(BaselineNameCollision.REPLACE_EXISTING)
}

/**
 * What this import's baseline-name question resolved to.
 *
 * A THIRD STATE NEXT TO "ASKED AND ANSWERED" and "NOTHING TO ASK", and that third state is the
 * point of this type: a plain nullable [BaselineNameCollision] cannot tell a caller "there was
 * nothing to decide" apart from "there was something to decide and nobody did", and those two
 * must not be handled the same way -- the first lets the import proceed, the second must stop it.
 */
sealed class BaselineNameCollisionResolution {
  /** No colliding name existed. Nothing was asked, because there was nothing to ask about. */
  object NothingToAsk : BaselineNameCollisionResolution()

  /** Something collided, and an answer came back. */
  data class Answered(val policy: BaselineNameCollision) : BaselineNameCollisionResolution()

  /**
   * Something collided, and no answer came back -- the dialog was closed without clicking its one
   * button. The caller MUST NOT import anything in this case: see the "ohne Antwort passiert
   * nichts" requirement this mirrors from the calendar question of Teil B.
   */
  object Unanswered : BaselineNameCollisionResolution()
}

/**
 * Resolves [collidingNames] into a decision, asking [ask] AT MOST ONCE regardless of how many
 * names collided -- that single call is "einmal fragen, für alle".
 *
 * A PLAIN FUNCTION PARAMETER, NOT THE REAL DIALOG DIRECTLY, is what makes the three outcomes above
 * checkable without opening a real window: a test passes a lambda that returns a fixed answer (or
 * null, standing in for "closed without answering"), production code passes one that shows
 * [showBaselineNameCollisionDialog] and blocks for the real answer. [ask] is never called at all
 * when [collidingNames] is empty -- asking about nothing would contradict "the dialog falls
 * exactly once per import", which means once when there is something to decide, not once always.
 */
fun resolveBaselineNameCollision(
  collidingNames: List<String>,
  ask: (List<String>) -> BaselineCollisionAnswer?
): BaselineNameCollisionResolution {
  if (collidingNames.isEmpty()) {
    return BaselineNameCollisionResolution.NothingToAsk
  }
  return ask(collidingNames)
    ?.let { BaselineNameCollisionResolution.Answered(it.policy) }
    ?: BaselineNameCollisionResolution.Unanswered
}
