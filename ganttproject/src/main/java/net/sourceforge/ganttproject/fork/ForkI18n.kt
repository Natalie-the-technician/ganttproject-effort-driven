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

import biz.ganttproject.app.Localizer
import biz.ganttproject.app.LocalizedString
import biz.ganttproject.app.RootLocalizer
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

/**
 * Texts added by this fork.
 *
 * WHY A SEPARATE BUNDLE, and not the ordinary translation files:
 *
 * GanttProject keeps its translations in `biz.ganttproject.app.localization`, which is a git
 * SUBMODULE pointing at bardsoftware's repository. A string added there could not be pushed from
 * this fork, and the commit this fork records for the submodule would point at an object nobody
 * else can fetch — anyone cloning it would end up with a broken checkout. That is a technical
 * limitation, not a legal one.
 *
 * The obvious alternative — a second plugin contributing to the `net.sourceforge.ganttproject.l10n`
 * extension point — does NOT work either. `InternationalizationImpl.createTranslationFromFile`
 * picks its file with `find`, so the FIRST bundle providing `i18n_de_DE.properties` wins and every
 * further one is ignored. Bundles do not merge. (Checked in the source; see CLAUDE-NOTES.md.)
 *
 * So this fork carries its own bundle inside its own module and puts it IN FRONT of the stock
 * lookup: a key defined here is answered here, everything else falls through to GanttProject
 * unchanged. Nothing upstream is touched, which is the point — the fork stays mergeable, and
 * upstream could adopt the mechanism as it stands.
 *
 * Keys are prefixed with `fork.` so a collision with an upstream key is impossible.
 */
object ForkI18n : Localizer {

  /** Classpath root of the bundle. `resources/` is a library root, see `plugin.xml`. */
  private const val BASE_PATH = "/language/fork"

  /** English, used when the current language has no file and as the per-key fallback. */
  private val defaultBundle: Properties by lazy { load("$BASE_PATH/i18n.properties") }

  private val bundlesByLocale = mutableMapOf<String, Properties>()

  /**
   * Reads a properties file from the classpath. A missing file is not an error: it just means this
   * fork has no translation for that language, and the English text is used.
   *
   * UTF-8 explicitly. `Properties.load(InputStream)` assumes ISO-8859-1, which would turn every
   * umlaut in the German file into rubbish.
   */
  private fun load(path: String): Properties = Properties().also { properties ->
    ForkI18n::class.java.getResourceAsStream(path)?.use { stream ->
      stream.reader(Charsets.UTF_8).use(properties::load)
    }
  }

  /**
   * The files that may answer for [locale], most specific first: country, then language, then
   * English. More forgiving than the stock loader, which only ever looks for `lang_COUNTRY` and
   * therefore finds nothing for a plain `de` locale.
   */
  private fun bundlesFor(locale: Locale): List<Properties> {
    val candidates = mutableListOf<Properties>()
    if (locale.country.isNotEmpty()) {
      candidates.add(bundlesByLocale.getOrPut("${locale.language}_${locale.country}") {
        load("$BASE_PATH/i18n_${locale.language.lowercase()}_${locale.country.uppercase()}.properties")
      })
    }
    if (locale.language.isNotEmpty()) {
      candidates.add(bundlesByLocale.getOrPut(locale.language) {
        load("$BASE_PATH/i18n_${locale.language.lowercase()}.properties")
      })
    }
    candidates.add(defaultBundle)
    return candidates
  }

  /**
   * @return the text for [key] in [locale], or null if this fork does not define that key. Never
   * throws: a missing bundle or a missing key is a normal state, not a failure.
   */
  fun textOrNull(key: String, locale: Locale, vararg args: Any): String? =
    bundlesFor(locale).firstNotNullOfOrNull { it.getProperty(key) }
      ?.let { if (args.isEmpty()) it else MessageFormat.format(it, *args) }

  override fun create(key: String): LocalizedString = LocalizedString(key, this)

  override fun formatTextOrNull(key: String, vararg args: Any): String? =
    textOrNull(key, Locale.getDefault(), *args)
}

/**
 * The localizer the fork's own user interface asks.
 *
 * Looks in the fork bundle first and falls through to GanttProject's own texts, so a panel can mix
 * new labels with existing ones (`fork.effort.section` next to a plain `resources`) without caring
 * which bundle a key comes from.
 *
 * [RootLocalizer] is deliberately read on every call rather than captured once: it is a `var` that
 * gets reassigned during startup and in tests, and capturing it would freeze whatever happened to
 * be installed when this object was first touched.
 */
object ForkLocalizer : Localizer {
  override fun create(key: String): LocalizedString = LocalizedString(key, this)

  override fun formatTextOrNull(key: String, vararg args: Any): String? =
    ForkI18n.formatTextOrNull(key, *args) ?: RootLocalizer.formatTextOrNull(key, *args)
}

/**
 * Short form for user interface code: always returns something printable. An undefined key comes
 * back as the key itself, which is visible in the interface and therefore gets noticed, instead of
 * an empty label that looks like an ordinary blank.
 */
fun forkText(key: String, vararg args: Any): String = ForkLocalizer.formatText(key, *args)
