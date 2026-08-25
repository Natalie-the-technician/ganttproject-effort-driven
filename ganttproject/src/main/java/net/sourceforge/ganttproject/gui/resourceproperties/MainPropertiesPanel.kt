/*
Copyright 2025 Dmitry Barashev,  BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

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
package net.sourceforge.ganttproject.gui.resourceproperties

import biz.ganttproject.app.PropertySheetBuilder
import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.app.i18n
import biz.ganttproject.colorFromUiManager
import biz.ganttproject.core.option.ObservableChoice
import biz.ganttproject.core.option.ObservableDouble
import biz.ganttproject.core.option.ObservableMoney
import biz.ganttproject.core.option.ObservableString
import javafx.collections.FXCollections
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.GridPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.roles.Role
import net.sourceforge.ganttproject.roles.RoleManager
// [fork change] New imports for the Toggl token.
import biz.ganttproject.lib.fx.openInBrowser
import java.awt.Desktop
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.timetracking.TogglTokenOptions
import net.sourceforge.ganttproject.timetracking.ASK_IN_A_DIALOG
import net.sourceforge.ganttproject.timetracking.TokenKeyChange
import net.sourceforge.ganttproject.timetracking.tokenKeyChange
import net.sourceforge.ganttproject.timetracking.tokenFor
import net.sourceforge.ganttproject.timetracking.tokenKeyFor

class MainPropertiesPanel(private val resource: HumanResource) {
  val title: String = RootLocalizer.formatText("general")
  val fxComponent by lazy { getFxNode() }
  val validationErrors = FXCollections.observableArrayList<String>()

  private val nameOption = ObservableString("name", resource.name)
  private val phoneOption = ObservableString("phone", resource.phone)
  private val emailOption = ObservableString("email", resource.mail)
  private val roleOption = ObservableChoice<Role>("role", resource.role,
    RoleManager.Access.getInstance().getEnabledRoles().toList(), roleStringConverter)
  private val rateOption = ObservableMoney("standardRate", resource.standardPayRate)
  private val totalCostOption = ObservableMoney("totalCost", resource.totalCost).also {
    it.setWritable(false)
  }
  private val totalLoadOption = ObservableDouble("totalLoad", resource.totalLoad).also {
    it.setWritable(false)
  }
  /**
   * [fork change] The Toggl token of this person.
   *
   * Lives NOT in the project but in the application settings — the project file is shared and
   * lives in the Vault. See TogglTokenOptions; it also states there that the token is stored
   * encoded but not encrypted.
   */
  private val togglTokenOption = ObservableString(
    "togglToken", tokenFor(resource, TogglTokenOptions.tokens.value))

  private var onRequestFocus = {}

  private fun getFxNode() = StackPane().apply {
    background = Background(BackgroundFill("Panel.background".colorFromUiManager(), CornerRadii.EMPTY, Insets.EMPTY))
    val pane = PropertySheetBuilder(i18n).pane {
      stylesheet("/biz/ganttproject/task/TaskPropertiesDialog.css")
      title("section.main")
      text(nameOption)
      text(phoneOption)
      text(emailOption)
      dropdown(roleOption)

      skip()
      title("section.rate")
      money(rateOption)
      money(totalCostOption)
      numeric(totalLoadOption)

      // [fork change] Time tracking. Labels hard-wired, because the translation files live in
      // the submodule of the original repository and an unknown key would otherwise stand in the
      // dialog as the key.
      skip()
      title(TOGGL_SECTION_LABEL)
      text(togglTokenOption) {
        labelText = TOGGL_TOKEN_LABEL
      }
    }
    appendTogglTokenHint(pane.node)
    onRequestFocus = pane::requestFocus
    children.add(pane.node)
  }

  /**
   * [fork change] Hangs the hint underneath the token field.
   *
   * Attached to the grid afterwards, not through the DSL of the property sheet: that one knows
   * only rows made of a property plus an editor, a row made of a free node does not exist there.
   * Extending it for this would mean rebuilding original code — a hint is too little for that.
   *
   * Column 1 is the column of the input fields, so the hint stands underneath the field and not
   * underneath the labels. The row is the next free one; the token field is the last row of the
   * sheet, because the time tracking section above is built last.
   */
  private fun appendTogglTokenHint(paneNode: Node) {
    val grid = paneNode as? GridPane ?: return
    val nextRow = (grid.children.mapNotNull(GridPane::getRowIndex).maxOrNull() ?: 0) + 1
    grid.add(togglTokenHint(), 1, nextRow)
  }

  /**
   * [fork change] The hint itself: one sentence on where the token comes from, below it the link
   * to the help article.
   *
   * Without a browser, plain text with a visible URL remains — a link that does nothing would be
   * worse than none, and the address can still be typed off.
   */
  private fun togglTokenHint(): Node = VBox(2.0).also { box ->
    // The container must not set the column either: a VBox passes the largest preferred width
    // of its children upwards. Reasoning in asHint().
    box.prefWidth = 0.0
    box.maxWidth = Double.MAX_VALUE
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

  fun requestFocus() = onRequestFocus()

  fun save() {
    // [fork change] The key of the token store is formed from the e-mail or the name - and BOTH
    // are changed in the lines directly below. The old key therefore has to be fixed beforehand,
    // otherwise the token stays lying under it: unfindable for the person (the connection check
    // reports "no token" although they have entered one) and left behind as a secret in
    // ~/.ganttproject. See movedToken.
    val previousTokenKey = tokenKeyFor(resource)

    nameOption.ifChanged(resource::setName)
    phoneOption.ifChanged(resource::setPhone)
    emailOption.ifChanged(resource::setMail)
    roleOption.ifChanged(resource::setRole)
    rateOption.ifChanged(resource::setStandardPayRate)
    saveTogglToken(previousTokenKey)
  }

  /**
   * [fork change] The token goes into the application settings, not into the project. An empty
   * field removes the entry.
   *
   * Deliberately NOT hung off `togglTokenOption.ifChanged`: the entry has to move even when only
   * the e-mail address was changed and the token field stayed untouched. That is precisely the
   * most frequent case — create a resource with a name, enter a token, add the address later.
   *
   * A write happens only when the store really changes, so that a dialog in which nobody touched
   * anything does not touch the settings file.
   */
  private fun saveTogglToken(previousTokenKey: String) {
    val outcome = tokenKeyChange(
      storedTokens = TogglTokenOptions.tokens.value,
      previousKey = previousTokenKey,
      newKey = tokenKeyFor(resource),
      editedToken = togglTokenOption.value?.trim().orEmpty())

    when (outcome) {
      is TokenKeyChange.Unchanged -> Unit
      is TokenKeyChange.Move -> TogglTokenOptions.tokens.value = outcome.tokens
      // [fork change] Formerly this overwrote silently. If a DIFFERENT token already lies under
      // the new address, every outcome costs a secret -- that is not for this dialog to decide but
      // for the person. The resource table follows the same rule.
      is TokenKeyChange.Collision ->
        ASK_IN_A_DIALOG.ask(outcome) { chosen -> TogglTokenOptions.tokens.value = chosen }
    }
  }

}

// [fork change] Labels from this fork's own text bundle. The original's translation files live
// in a submodule that cannot be written to from this fork; see ForkI18n.kt.
private val TOGGL_SECTION_LABEL get() = forkText("fork.toggl.section")
private val TOGGL_TOKEN_LABEL get() = forkText("fork.toggl.token")
private val TOGGL_TOKEN_HINT get() = forkText("fork.toggl.token.hint")
private val TOGGL_TOKEN_LINK get() = forkText("fork.toggl.token.link")

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

private val roleStringConverter = object : StringConverter<Role>() {
  override fun toString(role: Role): String  = role.name
  override fun fromString(string: String): Role? = RoleManager.Access.getInstance().getRole(string)
}

private val i18n = i18n {
  // We will search for the translation corresponding to a structured key in the current language only.
  default(withFallback = false)
  prefix("option.personProperties.main") {
    // If there is no translation, we'll search for the translation corresponding to the previously used unstructured key,
    // again in the current language only.
    default(withFallback = false)
    transform { key ->
      val key1 = when {
        key.endsWith(".label") -> key.removeSuffix(".label")
        else -> key
      }
      val map = mapOf(
        "phone" to "colPhone",
        "email" to "colMail",
        "role" to "colRole",
        "standardRate" to "colStandardRate",
        "totalCost" to "colTotalCost",
        "totalLoad" to "colTotalLoad",
        "section.rate" to "optionGroup.resourceRate.label"
      )
      map[key1] ?: key1
    }
    fallback {
      // Finally, we'll use the English translation of a structured key.
      default()
      prefix("option.personProperties.main")
    }
  }
}
