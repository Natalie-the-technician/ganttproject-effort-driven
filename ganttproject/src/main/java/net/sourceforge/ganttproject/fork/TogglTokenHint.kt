/*
Copyright 2026

NEW FILE IN THIS FORK — not present in the original GanttProject.

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

import biz.ganttproject.lib.fx.openInBrowser
import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.GanttOptions
import java.awt.Desktop

/**
 * [fork change] The hint as the running program builds it: the two questions asked, the box built
 * from the answers.
 *
 * A function of its own, and outside the panel class, for one reason: it is the only place where
 * `SecretStore.isAvailable` is turned into a hint, and that step has to be measurable. Inside the
 * class it would be reachable only by building the whole property sheet, and the mistake worth
 * catching -- somebody putting a constant where the probe belongs -- would then only ever show on
 * a screen.
 *
 * `SecretStore.isAvailable` runs a real probe against a real keyring; it is not a reading of
 * `os.name`. `GanttOptions.getOptionsFile` computes the path and touches no file.
 */
fun togglTokenHint(): VBox =
  togglTokenHintBox(SecretStore.isAvailable, GanttOptions.getOptionsFile().path)

// [fork change] Labels from this fork's own text bundle. The original's translation files live
// in a submodule that cannot be written to from this fork; see ForkI18n.kt.
private val TOGGL_TOKEN_HINT get() = forkText("fork.toggl.token.hint")
private val TOGGL_TOKEN_LINK get() = forkText("fork.toggl.token.link")

/**
 * [fork change] The hint under the token field: where the token comes from, the link to the help
 * article — and, WHERE THERE IS NO KEY STORE, what happens to the token that is typed in.
 *
 * Without a browser, plain text with a visible URL remains — a link that does nothing would be
 * worse than none, and the address can still be typed off.
 *
 * ═══ THE PLAIN-TEXT WARNING ═══
 *
 * Where no keyring can be reached, `TokenStore` writes the token into the settings file
 * UNENCRYPTED. That is deliberate and it stays: unlike a password a token cannot be typed again
 * from memory, it has to be fetched from the Toggl website, so refusing to store it would drive
 * people to keep it in a text file next to the program instead. The decision is Natalie's, taken
 * on 09.09.2026; the reasoning with its costs is in the report of that day, section 6.
 *
 * What was missing was that anybody was TOLD. Until now the only trace was one line in the log,
 * and the log is not where a person looks. This is the same fact where the decision is actually
 * taken: in the field, at the moment the token is pasted in, while it can still be decided not to.
 *
 * WHY A LINE UNDER THE FIELD AND NOT SOMETHING ELSE:
 *
 *  * NOT AN EXPANDER. The one thing a warning must not need is to be looked for. Everything a
 *    person has to weigh has to be readable without a click.
 *  * NOT A DIALOG ON SAVE. By then the token is entered. The moment where the fact changes what
 *    somebody does is the moment of typing, and that is where the field is.
 *  * NOT IN THE SECTION HEADING. It would then stand there on every machine, including those with
 *    a keyring, where it is not true.
 *  * ABOVE the "where do I find my token" hint, not below it: it is the sentence that may keep the
 *    field empty, and it should be read before the one that explains how to fill it.
 *
 * IT NEVER SHOWS THE TOKEN. Not shortened, not masked, not at all — this function is not given
 * one. What it names is the FILE and who can read it, and the file name comes from
 * `GanttOptions.getOptionsFile`, not from a string that could drift away from the truth.
 *
 * @param secretStoreAvailable whether a platform key store really answers on this machine. The
 * warning appears when it does NOT, and it must not appear when it does — a warning that always
 * comes is one that is always ignored.
 * @param optionsFilePath the settings file the token would go into, in full.
 */
fun togglTokenHintBox(secretStoreAvailable: Boolean, optionsFilePath: String): VBox = VBox(2.0).also { box ->
  // The container must not set the column either: a VBox passes the largest preferred width
  // of its children upwards. Reasoning in asHint().
  box.prefWidth = 0.0
  box.maxWidth = Double.MAX_VALUE
  if (!secretStoreAvailable) {
    box.children.add(plainTextWarning(optionsFilePath))
  }
  box.children.add(Label(TOGGL_TOKEN_HINT).asHint())
  box.children.add(
    if (canBrowse()) {
      Hyperlink(TOGGL_TOKEN_LINK).also { link ->
        link.onAction = EventHandler { openInBrowser(TOGGL_TOKEN_HELP_URL) }
      }
    } else {
      Label("$TOGGL_TOKEN_LINK $TOGGL_TOKEN_HELP_URL").asHint()
    }
  )
}

/**
 * [fork change] The warning line itself.
 *
 * The sign is a `graphic` rather than part of the sentence, for two reasons: a wrapped sentence
 * whose second line starts under the sign reads as if the sign belonged to that line, and a symbol
 * put into the translation files is a symbol a translation can lose. It is the same reasoning that
 * keeps [TOGGL_TOKEN_HELP_URL] out of those files.
 *
 * The colours are the ones the original already uses for "look at this": `#ffca28` and `#424242`
 * from `btn-attention` in `TaskPropertiesDialog.css`. Set inline rather than as a style class,
 * because that stylesheet belongs to the original and this fork does not write into it.
 */
private fun plainTextWarning(optionsFilePath: String): Label =
  Label(forkText("fork.toggl.token.plaintext", optionsFilePath)).asHint().also { label ->
    label.graphic = Label(WARNING_SIGN)
    label.contentDisplay = ContentDisplay.LEFT
    label.graphicTextGap = 6.0
    label.alignment = Pos.TOP_LEFT
    label.style = PLAIN_TEXT_WARNING_STYLE
  }

/**
 * [fork change] U+26A0. In the code and not in the translation files: it says the same thing in
 * every language, and a sign a translator has to copy is a sign that goes missing in one of them.
 */
private const val WARNING_SIGN = "\u26A0"

/**
 * [fork change] Amber on a pale amber ground with a bar down the left, dark grey text.
 *
 * `#ffca28` and `#424242` are the original's own attention colours (`btn-attention` in
 * `TaskPropertiesDialog.css`), so the warning does not look like something from a different
 * program. Inline instead of a style class: the stylesheet is the original's file.
 */
private const val PLAIN_TEXT_WARNING_STYLE =
  "-fx-background-color: #fff8e1; -fx-border-color: #ffca28; -fx-border-width: 0 0 0 3; " +
    "-fx-padding: 4 6 4 6; -fx-text-fill: #424242;"

/**
 * [fork change] The help article, not the profile page itself.
 *
 * Deliberately not a deep link: Toggl may rebuild the profile page, the help article is more
 * likely to outlast that. The address stands as a constant in the code and NOT in the language
 * files — it is the same in every language, and a translated link would be a link nobody
 * maintains.
 */
private const val TOGGL_TOKEN_HELP_URL = "https://support.toggl.com/where-is-my-api-key-located"

/**
 * [fork change] The hint is to TAKE the grid column, not to set it.
 *
 * A label with `isWrapText` reports the UNWRAPPED sentence as its preferred width, and in the grid
 * that becomes the column width. A fixed cap of 420 px used to stand here against it. That cap did
 * not only limit the column, it PULLED IT OPEN: measured, the field column was 346 px wide in the
 * original and 419 px in the fork, the dialog 496 px against 569 px accordingly.
 *
 * Without a preferred width the hint asks for no space at all. The column therefore takes its width
 * from the token field above it, and the hint wraps at exactly that width. `maxWidth` stays open,
 * because otherwise the grid does not stretch the node to the cell width and the hint would
 * collapse to its minimum.
 */
private fun Label.asHint(): Label = also {
  it.isWrapText = true
  it.prefWidth = 0.0
  it.maxWidth = Double.MAX_VALUE
}

/**
 * [fork change] Is there a browser that can be called?
 *
 * NOT through isBrowseSupported() in biz/ganttproject/lib/fx/Desktop.kt:39 — that calls
 * Desktop.getDesktop() without checking isDesktopSupported() first and then throws an
 * UnsupportedOperationException instead of returning false. A bug in the original; here it is
 * merely worked around, not fixed.
 */
private fun canBrowse(): Boolean = try {
  Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
} catch (e: Exception) {
  false
}
