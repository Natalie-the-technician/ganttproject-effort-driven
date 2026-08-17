/*
Copyright 2026

NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.

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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Tests the MECHANISM of the fork's own text bundle.
 *
 * On this branch the bundle carries no keys — the texts belong to the branches that add the
 * features they label. What is checked here is therefore only what holds without any content: the
 * bundle is found on the classpath, an undefined key is a normal state rather than an error, and
 * the lookup walks its locale chain without throwing.
 *
 * WHAT THESE TESTS DO NOT PROVE, found by counter-test and worth stating plainly: they cannot tell
 * a production-correct resource path from a wrong one. `ganttproject/build.gradle` declares BOTH
 * `src/main/resources/resources` (as a classpath entry, line 44) and `src/main/resources` (as a
 * resource root, line 114), so under test the bundle answers to `/language/fork/…` AND to
 * `/resources/language/fork/…`. In the packaged plugin only the first works, because `plugin.xml`
 * makes `resources/` the library root. Setting the path constant to the second value therefore
 * leaves every test here green and breaks the built application.
 *
 * That claim needs the built artifact, so it is checked there instead.
 *
 * REMOVED ON THIS BRANCH, because every one of them asserts a CONCRETE key and no key exists here.
 * They live on the branch that introduces the keys they check:
 *  - german texts come from the german file            (fork.effort.actualHours, fork.toggl.section)
 *  - english texts come from the default file          (fork.effort.actualHours)
 *  - an untranslated language falls back to english    (fork.effort.actualHours)
 *  - a language without a country finds the translation(fork.effort.actualHours)
 *  - umlauts survive being read from the file          (fork.toggl.checkConnection)
 *  - every german key has an english counterpart       (vacuous with an empty bundle: both key
 *                                                       sets are empty, and its own guard against
 *                                                       exactly that would fail here)
 *  - a translated column name does not stop the column being found (effort_hours)
 *  - a rejected split explains itself with its numbers (placeholder substitution — needs a key
 *                                                       that carries arguments)
 *  - no english exception text reaches the connection check message
 *  - the successful check names the person and the count
 *  - unreadable entries are mentioned in the message
 *  - button labels are short enough not to be cut off  (fork.toggl.collision.*)
 *  - the dialog text still names what each choice costs(fork.toggl.collision.choice)
 */
class ForkI18nTest {

  /**
   * The bundle is on the classpath. Deliberately the first assertion: if this fails, every other
   * test in this file fails too, and the reason is the file, not the translation.
   *
   * Note the limit stated above — this says the file is reachable under test, not that it is
   * packaged where the running application looks for it.
   */
  @Test
  fun `the fork bundle is found on the classpath`() {
    assertNotNull(ForkI18n::class.java.getResourceAsStream("/language/fork/i18n.properties"),
      "the fork bundle is not on the classpath — check the resources root in build.gradle")
  }

  /** The German file has to be reachable too, otherwise a translation could never be found. */
  @Test
  fun `the german bundle is found on the classpath`() {
    assertNotNull(ForkI18n::class.java.getResourceAsStream("/language/fork/i18n_de.properties"),
      "the german bundle is not on the classpath — check the resources root in build.gradle")
  }

  /** An undefined key is a normal state — the caller decides what to do, so: null, not an error. */
  @Test
  fun `an unknown key yields null`() {
    assertNull(ForkI18n.textOrNull("fork.gibt.es.nicht", Locale.GERMANY))
  }

  /**
   * `forkText` must always return something printable. The key itself is deliberate: an empty
   * label looks like an ordinary blank and hides the mistake, a visible "fork.gibt.es.nicht" does
   * not.
   */
  @Test
  fun `an unknown key shows up as itself rather than as a blank`() {
    assertEquals("fork.gibt.es.nicht", forkText("fork.gibt.es.nicht"))
  }

  /**
   * The locale chain itself, which is the part that has no content to depend on.
   *
   * `bundlesFor` tries language_COUNTRY, then language, then the English file. Each step reads a
   * file that may not exist, and a missing file must stay a normal state rather than an error —
   * there is deliberately no French bundle, and a plain `de` without a region has no
   * `i18n_de_DE.properties` to find. Walked here for a locale WITH a country, one WITHOUT, one
   * this fork was never translated into, and the root locale.
   */
  @Test
  fun `the locale chain is walked without throwing, whatever the locale`() {
    listOf(Locale.GERMANY, Locale.US, Locale.FRANCE, Locale("de"), Locale.ROOT).forEach { locale ->
      assertNull(ForkI18n.textOrNull("fork.gibt.es.nicht", locale),
        "an undefined key must stay undefined in $locale instead of failing")
    }
  }
}
