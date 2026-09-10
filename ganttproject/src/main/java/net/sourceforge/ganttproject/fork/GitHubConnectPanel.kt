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

import javafx.event.EventHandler
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import net.sourceforge.ganttproject.GanttOptions
import net.sourceforge.ganttproject.timetracking.OptionGitHubTokenStorage
import net.sourceforge.ganttproject.util.BrowserControl
import java.awt.Desktop

/**
 * [fork change] What the connect window shows. A plain value, and deliberately a small one.
 *
 * NOTE WHAT IS NOT IN IT: the device code. The window cannot show it because it is never given it,
 * and that is a stronger guarantee than remembering not to print it. The user code and the address
 * are the whole of what a person needs.
 */
data class GitHubConnectUi(
  /** The eight characters to type, once GitHub has sent them. */
  val userCode: String? = null,
  /** Where to type them — `https://github.com/login/device`. */
  val verificationUri: String? = null,
  /** Whether a token pair is on file. */
  val connected: Boolean = false,
  /** What happened, already a sentence. */
  val message: String? = null,
)

/**
 * [fork change] The box as the running program builds it: the two questions asked, the box built
 * from the answers.
 *
 * A function of its own for the same reason as [togglTokenHint]: it is the only place where
 * `SecretStore.isAvailable` becomes a warning, and that step has to be measurable. Inside a window
 * class it would be reachable only by opening the window.
 */
fun gitHubConnectBox(state: GitHubConnectUi): VBox =
  gitHubConnectBox(state, SecretStore.isAvailable, GanttOptions.getOptionsFile().path)

/**
 * [fork change] Connecting the hour journal to GitHub: the code, the address, and what came of it.
 *
 * ## Why the code is a read-only text field and not a label
 *
 * GitHub's own documentation makes the point that the browser may be ON ANOTHER DEVICE, and for a
 * person sitting at a desktop with a phone in hand that is a real case rather than a curiosity. So
 * the code has to survive two different journeys: being read out across a room, and being pasted.
 * A JavaFX `Label` can do neither well — its text cannot be selected at all. A `TextField` with
 * [TextField.isEditable] false can be selected, copied with the keyboard and read by a screen
 * reader, and it still cannot be typed into.
 *
 * MONOSPACE AND LARGE, because the alphabet GitHub uses for these codes contains characters a
 * proportional font renders nearly alike. Somebody transcribing `0` as `O` gets
 * `incorrect_device_code` and no hint as to which character was wrong.
 *
 * ## Why the address is a link and also a piece of text
 *
 * Where there is a browser it is a link. Where there is none — a stripped-down machine, a server —
 * a link that does nothing would be worse than none, so the address stands as plain text and can
 * be typed off. The same rule as [togglTokenHintBox] and the same reason.
 *
 * ## The warning about the settings file
 *
 * Where no key store answers, the sign-in goes into `~/.ganttproject` unencrypted (see
 * [OptionGitHubTokenStorage]). The log records that, but the log is not where a person looks. It
 * is said HERE, above the code, in the window where somebody is about to connect and can still
 * decide not to.
 *
 * @param secretStoreAvailable whether a platform key store really answers on this machine. It must
 * NOT appear when one does — a warning that always comes is one that is always ignored, and it
 * would be untrue on a machine where the sign-in really is in the keyring.
 * @param optionsFilePath the settings file the pair would go into, in full.
 */
fun gitHubConnectBox(
  state: GitHubConnectUi,
  secretStoreAvailable: Boolean,
  optionsFilePath: String,
): VBox = VBox(6.0).also { box ->
  // The container must not set the column width either: a VBox passes the largest preferred width
  // of its children upwards. Reasoning in TogglTokenHint.asHint().
  box.prefWidth = 0.0
  box.maxWidth = Double.MAX_VALUE

  if (!secretStoreAvailable) {
    box.children.add(
      plainTextWarningLabel(
        forkText(
          "fork.github.plaintext",
          optionsFilePath,
          OptionGitHubTokenStorage.GITHUB_TOKENS_SETTING
        )
      )
    )
  }

  state.userCode?.let { code ->
    box.children.add(Label(forkText("fork.github.step")).asWrappingHint())
    box.children.add(codeField(code))
    box.children.add(addressNode(state.verificationUri.orEmpty()))
    box.children.add(Label(forkText("fork.github.otherDevice")).asWrappingHint())
  }

  if (state.userCode == null) {
    box.children.add(
      Label(
        if (state.connected) forkText("fork.github.connected") else forkText("fork.github.intro")
      ).asWrappingHint()
    )
  }

  state.message?.let { box.children.add(Label(it).asWrappingHint()) }
}

/**
 * The code itself: selectable, copyable, and not typeable into.
 *
 * `isEditable = false` rather than `isDisable = true`. A disabled field is greyed out AND cannot be
 * selected, which would take away the copying this exists for.
 */
private fun codeField(code: String): TextField = TextField(code).also { field ->
  field.isEditable = false
  field.style = CODE_STYLE
  field.maxWidth = Double.MAX_VALUE
  field.prefWidth = 0.0
}

/**
 * `-fx-font-size: 24px` against the roughly 12–13 px of ordinary text: this is the one thing on the
 * window somebody has to read from a distance and copy without a mistake. Monospace so that `0` and
 * `O`, `1` and `l` cannot be confused. Letter spacing so a run of eight characters can be taken in
 * as eight rather than as a word.
 *
 * Inline rather than a style class, because the stylesheet is the original's file and this fork
 * does not write into it — the same rule as the amber warning next door.
 */
private const val CODE_STYLE =
  "-fx-font-family: monospace; -fx-font-size: 24px; -fx-letter-spacing: 2px; " +
    "-fx-alignment: center; -fx-background-color: transparent;"

/**
 * The address, as a link where something can open it and as plain text where nothing can.
 *
 * [BrowserControl.displayURL] rather than `openInBrowser`: it is what the rest of the program uses
 * for a web address (`MainPropertiesPanel.kt:238`, `ProjectSettingsPanel.java:85`) and it returns
 * whether it managed, which `openInBrowser` does not.
 */
private fun addressNode(uri: String) =
  if (canBrowseForGitHub()) {
    Hyperlink(uri).also { link ->
      link.contentDisplay = ContentDisplay.TEXT_ONLY
      link.onAction = EventHandler { BrowserControl.displayURL(uri) }
    }
  } else {
    Label(uri).asWrappingHint()
  }

/**
 * Is there a browser that can be called?
 *
 * NOT through `isBrowseSupported()` in `biz/ganttproject/lib/fx/Desktop.kt:39` — that calls
 * `Desktop.getDesktop()` without checking `isDesktopSupported()` first and then throws instead of
 * returning false. A bug in the original; here it is worked around, not fixed. The same workaround
 * as `TogglTokenHint.canBrowse`, and duplicated rather than shared because sharing it would read
 * as if somebody had decided the original's function is beyond repair.
 */
private fun canBrowseForGitHub(): Boolean = try {
  Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
} catch (e: Exception) {
  false
}

/** The same "take the column, do not set it" rule as `TogglTokenHint.asHint`. */
private fun Label.asWrappingHint(): Label = also {
  it.isWrapText = true
  it.prefWidth = 0.0
  it.maxWidth = Double.MAX_VALUE
}
