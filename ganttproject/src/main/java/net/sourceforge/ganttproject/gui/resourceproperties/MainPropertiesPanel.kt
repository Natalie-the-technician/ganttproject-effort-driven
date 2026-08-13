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
import javafx.geometry.Insets
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.StackPane
import javafx.util.StringConverter
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.roles.Role
import net.sourceforge.ganttproject.roles.RoleManager
// [Fork-Aenderung] Neue Importe fuer den Toggl-Token.
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
   * [Fork-Aenderung] Der Toggl-Token dieser Person.
   *
   * Liegt NICHT im Projekt, sondern in den Anwendungseinstellungen — die Projektdatei wird
   * geteilt und liegt im Vault. Siehe TogglTokenOptions; dort steht auch, dass der Token
   * kodiert, aber nicht verschluesselt abgelegt wird.
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

      // [Fork-Aenderung] Zeiterfassung. Beschriftungen fest verdrahtet, weil die
      // Uebersetzungsdateien im Submodul des Original-Repositories liegen und ein unbekannter
      // Schluessel sonst als Schluessel im Dialog stuende.
      skip()
      title(TOGGL_SECTION_LABEL)
      text(togglTokenOption) {
        labelText = TOGGL_TOKEN_LABEL
      }
    }
    onRequestFocus = pane::requestFocus
    children.add(pane.node)
  }

  fun requestFocus() = onRequestFocus()

  fun save() {
    // [Fork-Aenderung] Der Schluessel der Token-Ablage wird aus E-Mail bzw. Name gebildet - und
    // BEIDE werden in den Zeilen direkt darunter geaendert. Deshalb muss der alte Schluessel
    // vorher feststehen, sonst bleibt der Token unter ihm liegen: fuer die Person unauffindbar
    // (der Verbindungstest meldet "kein Token", obwohl sie einen eingetragen hat) und als
    // Geheimnis in ~/.ganttproject zurueck. Siehe movedToken.
    val previousTokenKey = tokenKeyFor(resource)

    nameOption.ifChanged(resource::setName)
    phoneOption.ifChanged(resource::setPhone)
    emailOption.ifChanged(resource::setMail)
    roleOption.ifChanged(resource::setRole)
    rateOption.ifChanged(resource::setStandardPayRate)
    saveTogglToken(previousTokenKey)
  }

  /**
   * [Fork-Aenderung] Der Token wandert in die Anwendungseinstellungen, nicht ins Projekt. Ein
   * leeres Feld entfernt den Eintrag.
   *
   * Bewusst NICHT an `togglTokenOption.ifChanged` gehaengt: der Eintrag muss auch dann umziehen,
   * wenn nur die E-Mail-Adresse geaendert wurde und das Token-Feld unberuehrt blieb. Genau das ist
   * der haeufigste Fall — Ressource mit Namen anlegen, Token eintragen, spaeter die Adresse
   * nachtragen.
   *
   * Geschrieben wird nur, wenn sich der Speicher wirklich aendert, damit ein Dialog, in dem
   * niemand etwas angefasst hat, die Einstellungsdatei nicht anfasst.
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
      // [Fork-Aenderung] Frueher wurde hier still ueberschrieben. Liegt unter der neuen Adresse
      // bereits ein ANDERER Token, kostet jeder Ausgang ein Geheimnis -- das entscheidet nicht
      // dieser Dialog, sondern die Person. Der Ressourcentabelle liegt dieselbe Regel zugrunde.
      is TokenKeyChange.Collision ->
        ASK_IN_A_DIALOG.ask(outcome) { chosen -> TogglTokenOptions.tokens.value = chosen }
    }
  }

}

// [Fork-Aenderung] Beschriftungen aus dem eigenen Textbuendel dieses Forks. Die
// Uebersetzungsdateien des Originals liegen in einem Submodul, das aus diesem Fork nicht
// beschrieben werden kann; siehe ForkI18n.kt.
private val TOGGL_SECTION_LABEL get() = forkText("fork.toggl.section")
private val TOGGL_TOKEN_LABEL get() = forkText("fork.toggl.token")

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
