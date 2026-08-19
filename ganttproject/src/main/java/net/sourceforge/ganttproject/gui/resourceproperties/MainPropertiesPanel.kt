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
// [Fork-Aenderung] Neue Importe fuer den Toggl-Token.
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
    appendTogglTokenHint(pane.node)
    onRequestFocus = pane::requestFocus
    children.add(pane.node)
  }

  /**
   * [Fork-Aenderung] Haengt den Hinweis unter das Token-Feld.
   *
   * Nachtraeglich ins Raster gehaengt, nicht ueber die DSL des Property-Blatts: die kennt nur
   * Zeilen aus Eigenschaft plus Editor, eine Zeile aus einem freien Knoten gibt es dort nicht. Sie
   * dafuer zu erweitern hiesse, Originalcode umzubauen — dafuer ist ein Hinweis zu wenig.
   *
   * Spalte 1 ist die Spalte der Eingabefelder, der Hinweis steht damit unter dem Feld und nicht
   * unter den Beschriftungen. Die Zeile ist die naechste freie; das Token-Feld ist die letzte Zeile
   * des Blatts, weil der Abschnitt Zeiterfassung oben als letzter aufgebaut wird.
   */
  private fun appendTogglTokenHint(paneNode: Node) {
    val grid = paneNode as? GridPane ?: return
    val nextRow = (grid.children.mapNotNull(GridPane::getRowIndex).maxOrNull() ?: 0) + 1
    grid.add(togglTokenHint(), 1, nextRow)
  }

  /**
   * [Fork-Aenderung] Der Hinweis selbst: ein Satz, wo der Token herkommt, darunter der Verweis auf
   * den Hilfeartikel.
   *
   * Ohne Browser bleibt reiner Text mit sichtbarer URL stehen — ein Verweis, der nichts tut, waere
   * schlimmer als keiner, und abgetippt werden kann die Adresse immer noch.
   */
  private fun togglTokenHint(): Node = VBox(2.0).also { box ->
    // Auch der Behaelter darf die Spalte nicht setzen: eine VBox reicht die groesste
    // Vorzugsbreite ihrer Kinder nach oben weiter. Begruendung siehe asHint().
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
private val TOGGL_TOKEN_HINT get() = forkText("fork.toggl.token.hint")
private val TOGGL_TOKEN_LINK get() = forkText("fork.toggl.token.link")

/**
 * [Fork-Aenderung] Der Hilfeartikel, nicht die Profilseite selbst.
 *
 * Bewusst keine Deep-Link-Adresse: die Profilseite kann Toggl umbauen, der Hilfeartikel ueberdauert
 * das eher. Die Adresse steht als Konstante im Code und NICHT in den Sprachdateien — sie ist in
 * jeder Sprache dieselbe, und ein uebersetzter Verweis waere ein Verweis, den niemand pflegt.
 */
private const val TOGGL_TOKEN_HELP_URL = "https://support.toggl.com/where-is-my-api-key-located"

/**
 * [Fork-Aenderung] Der Hinweis soll die Rasterspalte NEHMEN, nicht setzen.
 *
 * Ein Label mit `isWrapText` meldet als Vorzugsbreite den UNGEBROCHENEN Satz; im Raster wird daraus
 * die Spaltenbreite. Dagegen stand hier zuvor ein fester Deckel von 420 px. Der hat die Spalte aber
 * nicht nur begrenzt, sondern AUFGEZOGEN: gemessen war die Feldspalte im Original 346 px breit, im
 * Fork 419 px, der Dialog entsprechend 496 px gegen 569 px.
 *
 * Ohne Vorzugsbreite fragt der Hinweis keinen Platz mehr an. Die Spalte bekommt ihre Breite damit
 * vom Token-Feld darueber, und der Hinweis bricht auf genau diese Breite um. `maxWidth` bleibt
 * offen, weil das Raster den Knoten sonst nicht auf die Zellenbreite dehnt und der Hinweis auf
 * seine Mindestbreite zusammenfiele.
 */
private fun Label.asHint(): Label = also {
  it.isWrapText = true
  it.prefWidth = 0.0
  it.maxWidth = Double.MAX_VALUE
}

/**
 * [Fork-Aenderung] Gibt es einen Browser, den wir aufrufen koennen?
 *
 * NICHT ueber isBrowseSupported() in biz/ganttproject/lib/fx/Desktop.kt:39 — das ruft
 * Desktop.getDesktop() ohne vorherige isDesktopSupported()-Pruefung auf und wirft dann eine
 * UnsupportedOperationException, statt false zu liefern. Fehler des Originals; hier wird er nur
 * umgangen, nicht behoben.
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
