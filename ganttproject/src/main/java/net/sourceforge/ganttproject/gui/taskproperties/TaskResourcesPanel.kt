/*
Copyright 2003-2026 Dmitry Barashev, BarD Software s.r.o.

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
package net.sourceforge.ganttproject.gui.taskproperties

import biz.ganttproject.app.FXThread
import biz.ganttproject.app.PropertyPane
import biz.ganttproject.app.PropertyPaneBuilderImpl
import biz.ganttproject.app.RootLocalizer
import biz.ganttproject.core.option.ObservableBoolean
import biz.ganttproject.core.option.ObservableMoney
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.ChoiceBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.util.Callback
import javafx.util.StringConverter
import javafx.util.converter.DefaultStringConverter
import net.sourceforge.ganttproject.gui.AbstractTableAndActionsComponentFx
import net.sourceforge.ganttproject.gui.TableActionsModel
import net.sourceforge.ganttproject.gui.TableView2TableActionsModel
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.resource.HumanResourceManager
import net.sourceforge.ganttproject.roles.Role
import net.sourceforge.ganttproject.roles.RoleManager
import net.sourceforge.ganttproject.task.CostStub
import net.sourceforge.ganttproject.task.Task
import net.sourceforge.ganttproject.task.TaskMutator
// [fork change] New imports for effort-driven scheduling.
import biz.ganttproject.customproperty.CustomPropertyHolder
import net.sourceforge.ganttproject.task.algorithm.EffortDrivenProperties
import net.sourceforge.ganttproject.task.algorithm.EffortInput
import net.sourceforge.ganttproject.task.algorithm.actualEffortHours
import net.sourceforge.ganttproject.task.algorithm.effortHours
import net.sourceforge.ganttproject.task.algorithm.findEffortDefinition
import net.sourceforge.ganttproject.task.algorithm.hoursPerDay
import net.sourceforge.ganttproject.fork.forkText
import net.sourceforge.ganttproject.task.algorithm.parseEffortInput
import org.controlsfx.control.tableview2.TableColumn2
import org.controlsfx.control.tableview2.TableView2
import java.math.BigDecimal

/**
 * JavaFX panel for managing task resource assignments.
 */
class TaskResourcesPanel(
  private val task: Task,
  private val hrManager: HumanResourceManager,
  private val roleManager: RoleManager
) {
  private val tableItems: ObservableList<ResourceAssignmentRow> = FXCollections.observableArrayList()
  private val tableView = TableView2<ResourceAssignmentRow>()
  private val model = object : TableView2TableActionsModel<ResourceAssignmentRow>(tableView) {
    private val innerModel = ResourceAssignmentTableModel(task)

    override fun delete(indices: IntArray) {
      innerModel.delete(indices)
      refreshTable()
    }

    override fun onAdd() {
      FXThread.runLater {
        tableView.edit(tableItems.size - 1, tableView.columns[1])
        tableView.selectionModel.select(tableItems.size - 1)
      }
    }

    override fun refreshTable() {
      val currentSelection = selectedRow
      tableItems.clear()
      val assignments = innerModel.assignments
      assignments.forEach { assignment ->
        tableItems.add(ResourceAssignmentRow(assignment))
      }
      // The last row is for adding new assignments
      tableItems.add(ResourceAssignmentRow(null))
      selectedRow = currentSelection
    }

    fun commit() {
      innerModel.commit()
    }

    fun setValueAt(value: Any?, row: Int, col: Int) {
      innerModel.setValueAt(value, row, col)
    }
  }

  private val costIsCalculated = ObservableBoolean("option.taskProperties.cost.calculated.label", task.cost.isCalculated)
  private val costValue = ObservableMoney("option.taskProperties.cost.value", task.cost.value)

  val title: String = i18n.formatText("human")
  private val tableAndActions = AbstractTableAndActionsComponentFx(tableView, model)

  val fxComponent by lazy {
    getFxNode().also {
      model.refreshTable()
    }
  }

  private var selectedIndices = FXCollections.emptyObservableList<Int>()
  private var selectedRow: Int
    set(value) {
      selectRow(value)
    }
    get() = selectedIndices.firstOrNull() ?: -1
  private var selectRow: (Int) -> Unit = {}

  private fun getFxNode(): Node {
    tableView.apply {
      isEditable = true
      items = tableItems
      selectionModel.selectionMode = SelectionMode.SINGLE
      selectedIndices = selectionModel.selectedIndices
      selectRow = { selectionModel.select(it) }

      // ID column
      val idCol = TableColumn2<ResourceAssignmentRow, String>(i18n.formatText("id")).apply {
        setCellValueFactory { SimpleStringProperty(it.value.assignment?.resource?.id?.toString() ?: "") }
        prefWidth = 50.0
        isEditable = false
      }

      // Resource name column with combo box editor
      val nameCol = TableColumn2<ResourceAssignmentRow, HumanResource?>(i18n.formatText("resourcename")).apply {
        setCellValueFactory { SimpleObjectProperty(it.value.assignment?.resource) }
        setCellFactory { ResourceComboTableCell(hrManager) }
        setOnEditCommit { event ->
          val row = event.rowValue
          val newResource = event.newValue
          if (row.assignment == null) {
            if (newResource != null) {
              model.setValueAt(newResource, tableItems.size - 1, 1)
              model.refreshTable()
            }
          } else {
            model.setValueAt(newResource, event.tablePosition.row, 1)
            model.refreshTable()
          }
        }
        isEditable = true
        prefWidth = 200.0
      }

      // Load/Unit column
      val unitCol = TableColumn2<ResourceAssignmentRow, String>(i18n.formatText("unit")).apply {
        setCellValueFactory { SimpleStringProperty(it.value.assignment?.load?.toString() ?: "") }
        setCellFactory { TextFieldTableCell(DefaultStringConverter()) }
        setOnEditCommit { event ->
          try {
            model.setValueAt(event.newValue, event.tablePosition.row, 2)
          } catch (e: NumberFormatException) {
          }
          model.refreshTable()
        }
        isEditable = true
        prefWidth = 80.0
      }

      // Coordinator column
      val coordinatorCol = TableColumn2<ResourceAssignmentRow, Boolean>(i18n.formatText("coordinator")).apply {
        setCellValueFactory { SimpleBooleanProperty(it.value.assignment?.isCoordinator ?: false) }
        cellFactory = CheckBoxTableCell.forTableColumn(this)
        setOnEditCommit { event ->
          model.setValueAt(event.newValue, event.tablePosition.row, 3)
          model.refreshTable()
        }
        isEditable = true
        prefWidth = 100.0
      }

      // Role column
      val roleStringConverter: StringConverter<Role> = object : StringConverter<Role>() {
        override fun toString(role: Role?): String = role?.name ?: ""
        override fun fromString(value: String?): Role? {
          return roleManager.enabledRoles.find { it.name == value }
        }
      }
      val roleCol = TableColumn2<ResourceAssignmentRow, Role>(i18n.formatText("role")).apply {
        setCellValueFactory { SimpleObjectProperty(it.value.assignment?.roleForAssignment) }
        cellFactory = ChoiceBoxTableCell.forTableColumn(
          roleStringConverter,
          FXCollections.observableArrayList(roleManager.enabledRoles.toList())
        )
        setOnEditCommit { event ->
          model.setValueAt(event.newValue, event.tablePosition.row, 4)
          model.refreshTable()
        }
        isEditable = true
        prefWidth = 150.0
      }

      // [fork change] Axis A: does this person's absence block the task? Two independent
      // checkboxes and not one choice, because a person can block without contributing and
      // contribute without blocking.
      //
      // P1 only writes the value into the assignment and into the file. Nothing reads it yet --
      // the effect is P2 and P3.
      val blockingCol = TableColumn2<ResourceAssignmentRow, Boolean>(AXIS_LABEL_BLOCKING).apply {
        setCellValueFactory { cell -> axisProperty(cell.value.assignment, { it.isBlocking }, { a, v -> a.isBlocking = v }) }
        cellFactory = CheckBoxTableCell.forTableColumn(this)
        isEditable = true
        prefWidth = 130.0
      }

      // [fork change] Axis B, NEGATED: ticked means this person contributes no work that counts
      // towards the effort. Unticked is what the program does today, so an untouched checkbox
      // and an old file mean the same thing.
      val noEffortCol = TableColumn2<ResourceAssignmentRow, Boolean>(AXIS_LABEL_NO_EFFORT).apply {
        setCellValueFactory { cell -> axisProperty(cell.value.assignment, { it.isNoEffort }, { a, v -> a.isNoEffort = v }) }
        cellFactory = CheckBoxTableCell.forTableColumn(this)
        isEditable = true
        prefWidth = 110.0
      }

      // [fork change] New column: shows the daily hours of the resource that the duration
      // calculation computes with. DISPLAY ONLY - the daily hours apply globally to all tasks of
      // this resource and are therefore edited in the resource manager, not here. A second editor
      // in this place would move other tasks' dates without that being noticed in the task
      // dialog.
      // A fixed label instead of an i18n key: the translation files live in the submodule
      // biz.ganttproject.app.localization, which points at the original repository and must not
      // be written to from here. An unknown key would be displayed as the key
      // (RootLocalizer.formatText returns the key when an entry is missing).
      val hoursPerDayCol = TableColumn2<ResourceAssignmentRow, String>(EFFORT_LABEL_HOURS_PER_DAY).apply {
        setCellValueFactory { row ->
          val resource = row.value.assignment?.resource
          SimpleStringProperty(
            resource?.let { formatHours(it.hoursPerDay(hrManager.customPropertyManager)) } ?: "")
        }
        isEditable = false
        prefWidth = 90.0
      }

      // [fork change] blockingCol, noEffortCol and hoursPerDayCol are new, the remaining columns
      // are the original ones.
      columns.addAll(idCol, nameCol, unitCol, coordinatorCol, roleCol,
        blockingCol, noEffortCol, hoursPerDayCol)
    }

    // Create split layout with table and cost panel
//    val mainContent = HBox().apply {
//      spacing = 10.0
//      children.addAll(
//        tableView.apply { HBox.setHgrow(this, Priority.SOMETIMES) },
//        createCostPanel().apply {
//          HBox.setHgrow(this, Priority.SOMETIMES)
//        }
//      )
//    }
    val tableComponent = tableAndActions.fxComponent
    return BorderPane().apply {
      stylesheets.add("/biz/ganttproject/task/TaskPropertiesDialog.css")
      stylesheets.add("/biz/ganttproject/app/tables.css")
      stylesheets.add("/biz/ganttproject/app/buttons.css")
      styleClass.addAll("tab-contents", "pane-task-resources")
      center = tableComponent
      right = createCostPanel()
    }
  }

  // [fork change] ---- start: new block for effort-driven scheduling ----

  /**
   * Editor for the effort of this task, in hours. Empty means "no effort set", which switches the
   * feature off for this task and leaves its duration alone.
   */
  private val effortField = TextField().apply {
    prefColumnCount = 6
    text = task.effortHours(task.manager.customPropertyManager)?.let { formatHours(it) } ?: ""
  }

  /**
   * Writes the edited effort into the custom property holder that the properties dialog is about
   * to commit.
   *
   * This must NOT write to `task.customValues` directly. CustomColumnsPanel.save() replaces the
   * whole property set of the task with a copy it took when the dialog was opened, so a direct
   * write would be silently overwritten when the user presses OK. Going through the same holder
   * keeps a single write path.
   *
   * The property definition is created only when a value is actually entered, so that projects
   * which do not use the feature do not silently gain a column.
   */
  fun applyEffort(holder: CustomPropertyHolder) {
    val definitions = task.manager.customPropertyManager
    when (val input = parseEffortInput(effortField.text)) {
      is EffortInput.Clear ->
        // Only clear when the property exists; do not create it just to write nothing into it.
        // [fork change] Id OR name: a column created by the user carries the typed text in its
        // name only. With an id-only lookup such a field could no longer be cleared - the old
        // value would have stayed, without any indication.
        definitions.findEffortDefinition(EffortDrivenProperties.TASK_EFFORT_HOURS)?.let {
          holder.setValue(it, null)
        }
      is EffortInput.Hours ->
        holder.setValue(EffortDrivenProperties.findOrCreateTaskEffort(definitions), input.value)
      is EffortInput.Invalid -> Unit
    }
  }

  /**
   * Editor for the hours ACTUALLY spent on this task. Empty means "nothing recorded".
   *
   * Sits next to the planned effort on purpose: the two numbers are only useful side by side.
   * It is a plain record — see [Task.actualEffortHours]; typing a value here must never move a
   * date, change the completion percentage or touch the planned effort.
   */
  private val actualEffortField = TextField().apply {
    prefColumnCount = 6
    text = task.actualEffortHours(task.manager.customPropertyManager)?.let { formatHours(it) } ?: ""
  }

  /**
   * Writes the edited actual effort into the custom property holder that the properties dialog is
   * about to commit. Same rules as [applyEffort]:
   *
   * - never write to `task.customValues` directly, or CustomColumnsPanel.save() overwrites it with
   *   the copy it took when the dialog was opened,
   * - reuse [parseEffortInput] instead of parsing here, so that a comma keeps working and both
   *   fields accept exactly the same input,
   * - create the definition only when a value is actually entered.
   *
   * Invalid input leaves the stored value alone. It is deliberately NOT reset to the old text in
   * the field: a typo should stay visible for correction instead of vanishing on OK.
   */
  fun applyActualEffort(holder: CustomPropertyHolder) {
    val definitions = task.manager.customPropertyManager
    when (val input = parseEffortInput(actualEffortField.text)) {
      is EffortInput.Clear ->
        // Only clear when the property exists; do not create it just to write nothing into it.
        // [fork change] Id OR name, see applyEffort.
        definitions.findEffortDefinition(
          EffortDrivenProperties.TASK_EFFORT_ACTUAL_HOURS)?.let { holder.setValue(it, null) }
      is EffortInput.Hours ->
        holder.setValue(EffortDrivenProperties.findOrCreateTaskActualEffort(definitions), input.value)
      is EffortInput.Invalid -> Unit
    }
  }

  /** Adds the effort editors underneath the cost fields of the right hand pane. */
  private fun addEffortEditor(propertyPane: PropertyPane) {
    propertyPane.add(Label(EFFORT_LABEL_SECTION).apply {
      styleClass.add("section-title")
    }, 0, 3, 2, 1)
    propertyPane.add(Label(EFFORT_LABEL_EFFORT_HOURS), 0, 4)
    propertyPane.add(effortField, 1, 4)
    propertyPane.add(Label(EFFORT_LABEL_ACTUAL_HOURS), 0, 5)
    propertyPane.add(actualEffortField, 1, 5)
  }

  // [fork change] ---- end of the new block ----

  private fun createCostPanel(): Region {
    val propertyPane = PropertyPane()
    val builder = PropertyPaneBuilderImpl(i18n, propertyPane)
    val radioUi = builder.createRadioButtonOptionEditor(costIsCalculated, null)
    propertyPane.add(radioUi.yesButton, 0, 1)
    propertyPane.add(radioUi.noButton, 0, 2)
    costIsCalculated.value = task.cost.isCalculated
    costValue.setWritable(!costIsCalculated.value)

    costIsCalculated.addWatcher {
      costValue.setWritable(!costIsCalculated.value)
      costValue.value = task.cost.value
    }

    // Title
    propertyPane.add(Label(i18n.formatText("optionGroup.task.cost.label")).apply {
      styleClass.add("section-title")
    }, 0, 0, 2, 1)

    // Calculated cost radio button
    val calculatedValueLabel = Label(task.cost.calculatedValue.toPlainString())
    propertyPane.add(calculatedValueLabel, 1, 1)
    builder.createMoneyOptionEditor(costValue).also { propertyPane.add(it, 1, 2) }

    // [fork change] Effort field added underneath the cost fields.
    addEffortEditor(propertyPane)

    return propertyPane
  }

  fun commit(mutator: TaskMutator) {
    model.commit()
    val cost = if (costIsCalculated.value) {
      CostStub(BigDecimal.ZERO, true)
    } else {
      CostStub(costValue.value, false)
    }
    mutator.setCost(cost)
  }

  fun requestFocus() {
    selectedRow = 0
  }
}

// --------------------------------------------------------------------------------------------------------------------
private val i18n = RootLocalizer

// [fork change] Labels of the new controls. They live in this fork's own text bundle, not in the
// original's i18n files: those lie in a submodule that points at bardsoftware's repository and
// cannot be written to from this fork. Reasoning and mechanics see ForkI18n.kt.
private val EFFORT_LABEL_SECTION get() = forkText("fork.effort.section")
private val EFFORT_LABEL_EFFORT_HOURS get() = forkText("fork.effort.hours")
private val EFFORT_LABEL_HOURS_PER_DAY get() = forkText("fork.effort.hoursPerDay")
private val EFFORT_LABEL_ACTUAL_HOURS get() = forkText("fork.effort.actualHours")

// [fork change] Labels of the two assignment axes.
private val AXIS_LABEL_BLOCKING get() = forkText("fork.assignment.blocking")
private val AXIS_LABEL_NO_EFFORT get() = forkText("fork.assignment.noEffort")

/**
 * [fork change] The writable property behind an axis checkbox.
 *
 * WHY THIS AND NOT `setOnEditCommit`: `CheckBoxTableCell.forTableColumn(column)` does NOT start an
 * edit. It takes whatever the cell value factory returned and, if that is a `BooleanProperty`,
 * binds the checkbox to it BIDIRECTIONALLY -- `onEditCommit` is never fired. A factory that hands
 * out a fresh `SimpleBooleanProperty` on every call therefore swallows the click: the tick lands
 * in an object that nobody ever reads again.
 *
 * MEASURED ON 27.08.2026 in the running program: both ticks set in the dialog, Ok, save --
 * `<allocation ... blocking="false" no-effort="false"/>`, and the ticks were gone when the dialog
 * was reopened. With the listener below the same run writes `blocking="true"`.
 *
 * The write goes straight to the assignment, which is what the model's `setValueAt` does for the
 * coordinator column as well. For an assignment that already exists this is the live object; for
 * one just added in this dialog it is the mutator's stub, and the stub's values are copied over
 * in `ResourceAssignmentCollectionImpl.commit`.
 *
 * THE SAME DEFECT SITS IN THE ORIGINAL COORDINATOR COLUMN, which is wired exactly like the two
 * new ones were. It is NOT fixed here -- that is behaviour outside P1. Measured in the same run:
 * ticking "Coordinator" for a second person and saving leaves `responsible="false"` in the file.
 */
private fun axisProperty(
  assignment: net.sourceforge.ganttproject.task.ResourceAssignment?,
  read: (net.sourceforge.ganttproject.task.ResourceAssignment) -> Boolean,
  write: (net.sourceforge.ganttproject.task.ResourceAssignment, Boolean) -> Unit
): SimpleBooleanProperty =
  SimpleBooleanProperty(assignment?.let(read) ?: false).also { property ->
    property.addListener { _, _, ticked -> assignment?.let { write(it, ticked) } }
  }

/**
 * [fork change] New helper: display hours without a superfluous decimal place, so that the table
 * shows "8" instead of "8.0".
 */
private fun formatHours(hours: Double): String =
  if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()

// --------------------------------------------------------------------------------------------------------------------

/**
 * Table model for resource assignments.
 */
private class ResourceAssignmentTableModel(task: Task) {
  private val assignmentCollection = task.assignmentCollection
  private val mutator = assignmentCollection.createMutator()
  private val _assignments = assignmentCollection.assignments.toMutableList()

  val assignments: List<net.sourceforge.ganttproject.task.ResourceAssignment>
    get() = _assignments.toList()

  fun setValueAt(value: Any?, row: Int, col: Int) {
    if (row >= _assignments.size) {
      createAssignment(value)
    } else {
      updateAssignment(value, row, col)
    }
  }

  private fun updateAssignment(value: Any?, row: Int, col: Int) {
    val assignment = _assignments[row]
    when (col) {
      4 -> { // Role
        if (value is Role) {
          assignment.roleForAssignment = value
        }
      }
      3 -> { // Coordinator
        if (value is Boolean) {
          assignment.isCoordinator = value
        }
      }
      2 -> { // Load
        try {
          val load = value.toString().toFloat()
          assignment.load = load
        } catch (e: NumberFormatException) {
        }
      }
      1 -> { // Resource
        if (value == null) {
          assignment.delete()
          _assignments.removeAt(row)
        } else if (value is HumanResource) {
          val load = assignment.load
          val coord = assignment.isCoordinator
          // [fork change] Swapping the person replaces the assignment object. Without these two
          // the ticks set a moment ago would silently disappear.
          val blocking = assignment.isBlocking
          val noEffort = assignment.isNoEffort
          assignment.delete()
          mutator.deleteAssignment(assignment.resource)
          val newAssignment = mutator.addAssignment(value)
          newAssignment.load = load
          newAssignment.isCoordinator = coord
          newAssignment.isBlocking = blocking
          newAssignment.isNoEffort = noEffort
          _assignments[row] = newAssignment
        }
      }
    }
  }

  private fun createAssignment(value: Any?) {
    if (value is HumanResource) {
      val newAssignment = mutator.addAssignment(value)
      newAssignment.load = 100f
      newAssignment.isCoordinator = _assignments.isEmpty()
      newAssignment.roleForAssignment = value.role
      _assignments.add(newAssignment)
    }
  }

  fun delete(selectedRows: IntArray) {
    val toDelete = selectedRows.filter { it < _assignments.size }.map { _assignments[it] }
    toDelete.forEach { it.delete() }
    _assignments.removeAll(toDelete)
  }

  fun commit() {
    mutator.commit()
  }
}

/**
 * Row data class for the table.
 */
private class ResourceAssignmentRow(val assignment: net.sourceforge.ganttproject.task.ResourceAssignment?)

/**
 * Custom table cell for resource selection with combo box.
 */
private class ResourceComboTableCell(hrManager: HumanResourceManager) :
  TableCell<ResourceAssignmentRow, HumanResource?>() {

  private val comboBox = ComboBox<HumanResource>()
  private val resources = hrManager.resources.toList()

  init {
    comboBox.items.addAll(resources)
    comboBox.cellFactory = Callback { ResourceListCell() }
    comboBox.buttonCell = ResourceListCell()
    comboBox.maxWidth = Double.MAX_VALUE
    comboBox.setOnAction {
      if (isEditing) {
        commitEdit(comboBox.value)
      }
    }
  }

  override fun startEdit() {
    super.startEdit()
    comboBox.value = item
    graphic = comboBox
    text = null
  }

  override fun cancelEdit() {
    super.cancelEdit()
    graphic = null
    text = item?.name
  }

  override fun updateItem(item: HumanResource?, empty: Boolean) {
    super.updateItem(item, empty)
    if (empty || item == null) {
      text = null
      graphic = null
    } else {
      if (isEditing) {
        graphic = comboBox
        text = null
      } else {
        text = item.name
        graphic = null
      }
    }
  }
}

/**
 * List cell for displaying resources in combo box.
 */
private class ResourceListCell : ListCell<HumanResource>() {
  override fun updateItem(item: HumanResource?, empty: Boolean) {
    super.updateItem(item, empty)
    if (empty || item == null) {
      text = null
      graphic = null
    } else {
      text = item.name
    }
  }
}
