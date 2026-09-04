/*
GanttProject is an opensource project management tool.
Copyright (C) 2003-2011 GanttProject Team

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.sourceforge.ganttproject.gui;

import biz.ganttproject.core.calendar.GanttDaysOff;
import biz.ganttproject.customproperty.CustomPropertyManager;
import com.google.common.collect.Lists;
import javafx.scene.control.Tab;
import kotlin.Unit;
import net.sourceforge.ganttproject.action.CancelAction;
import net.sourceforge.ganttproject.action.OkAction;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.fork.HomeOfficePanelFx;
import net.sourceforge.ganttproject.fork.WorkWeekPanelFx;
import net.sourceforge.ganttproject.gui.resourceproperties.MainPropertiesPanel;
import net.sourceforge.ganttproject.gui.resourceproperties.ResourceAssignmentsPanelFx;
import net.sourceforge.ganttproject.gui.taskproperties.CustomColumnsPanel;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.storage.ProjectDatabase;
import net.sourceforge.ganttproject.task.TaskManager;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class GanttDialogPerson {
  private static final GanttLanguage language = GanttLanguage.getInstance();

  private final TaskManager myTaskManager;
  private final HumanResourceManager myResourceManager;
  private final HumanResource person;


  private final UIFacade myUIFacade;
  private final Runnable onHide;
  private ResourceAssignmentsPanelFx myAssignmentsPanel;
  private final MainPropertiesPanel mainPropertiesPanel;
  private final CustomColumnsPanel customColumnsPanel;
  /**
   * [fork change] The working week of this person — seven boxes and two buttons.
   *
   * A TAB OF ITS OWN, and next to the days off. The two belong together: the working week is the
   * grid, an absence is an exception inside it, and somebody looking for one will look where the
   * other is. It is NOT folded into the days-off tab, because that tab is a full-height list
   * editor built out of an upstream component ({@link DateIntervalListEditorFx}); putting a seven
   * box grid and two buttons under it would cramp both and would mean changing upstream code where
   * adding a tab changes none.
   *
   * The fork's other resource columns — utilisation, hours per day — stay in the custom columns
   * tab, and that is deliberate too: those are single values a text field expresses exactly. A
   * working week over time is not, which is why it needed a panel at all.
   */
  private final WorkWeekPanelFx workWeekPanel;
  /**
   * [fork change] Where this person works — the home office, as a weekly pattern and as periods.
   *
   * A TAB OF ITS OWN, after the working week, and the order of the three is the order of the
   * questions: the days off say WHETHER the person is there, the working week WHICH days are
   * theirs, the home office WHERE they are on such a day.
   *
   * IT IS EMPHATICALLY NOT PART OF THE DAYS-OFF TAB. Home office is not an absence — the person
   * works a full day and can simply not be given a task that has to be done on the premises.
   * Filing it under "days off" would be the one mistake that costs the plan working days that were
   * actually worked, so the two are kept apart on screen as well as in the model. See
   * {@link net.sourceforge.ganttproject.fork.HomeOffice}.
   */
  private final HomeOfficePanelFx homeOfficePanel;


  public GanttDialogPerson(HumanResourceManager resourceManager,
                           CustomPropertyManager customPropertyManager,
                           TaskManager taskManager,
                           ProjectDatabase projectDatabase,
                           UIFacade uiFacade,
                           HumanResource person,
                           Runnable onHide
                           ) {
    myResourceManager = resourceManager;
    myTaskManager = taskManager;
    myUIFacade = uiFacade;
    this.person = person;
    mainPropertiesPanel = new MainPropertiesPanel(person);
    customColumnsPanel = new CustomColumnsPanel(customPropertyManager, projectDatabase, CustomColumnsPanel.Type.RESOURCE,
      myUIFacade.getUndoManager(), person, myUIFacade.getResourceColumnList());
    // [fork change] Its JavaFX controls are built lazily, so this costs nothing off the FX thread.
    workWeekPanel = new WorkWeekPanelFx(person, customPropertyManager);
    // [fork change] Lazily built controls again, so this costs nothing off the FX thread either.
    homeOfficePanel = new HomeOfficePanelFx(person, customPropertyManager);
    this.onHide = onHide;
  }

  public void setVisible(boolean isVisible) {
    if (isVisible) {
      constructDaysOffPanel();
      constructAssignmentsPanel();
      OkAction okAction = new OkAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          // If called from JavaFX thread, the undo manager fails badly
          // along with the VM.
          SwingUtilities.invokeLater(() -> okButtonActionPerformed());
          onHide.run();
          myUIFacade.getActiveChart().focus();
        }
      };
      CancelAction cancelAction = new CancelAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          onHide.run();
          myUIFacade.getActiveChart().focus();
        }
      };

      var actions = Lists.newArrayList(okAction, cancelAction);
      PropertiesDialogKt.propertiesDialog(
        language.getCorrectedLabel("human"),
        "resourceProperties", actions,
        mainPropertiesPanel.getValidationErrors(),
        Lists.newArrayList(
          new PropertiesDialogTabProvider(
            tabPane -> {
              tabPane.getTabs().add(new Tab(mainPropertiesPanel.getTitle(), mainPropertiesPanel.getFxComponent()));
              return Unit.INSTANCE;
            },
            () -> {
              mainPropertiesPanel.requestFocus();
              return Unit.INSTANCE;
            }
          ),
          new PropertiesDialogTabProvider(
            tabPane -> {
              tabPane.getTabs().add(new Tab(language.getText("daysOff"), new DateIntervalListEditorFx(myDaysOffModel)));
              return Unit.INSTANCE;
            },
            () -> Unit.INSTANCE
          ),
          // [fork change] The working week, directly after the days off — see workWeekPanel.
          new PropertiesDialogTabProvider(
            tabPane -> {
              tabPane.getTabs().add(
                new Tab(ForkI18nKt.forkText("fork.column.workWeek"), workWeekPanel.getNode()));
              return Unit.INSTANCE;
            },
            () -> Unit.INSTANCE
          ),
          // [fork change] The home office, after the working week — see homeOfficePanel.
          new PropertiesDialogTabProvider(
            tabPane -> {
              tabPane.getTabs().add(
                new Tab(ForkI18nKt.forkText("fork.homeoffice.ui.tab"), homeOfficePanel.getNode()));
              return Unit.INSTANCE;
            },
            () -> Unit.INSTANCE
          ),
          new PropertiesDialogTabProvider(tabPane -> {
            tabPane.getTabs().add(new Tab(customColumnsPanel.getTitle(), customColumnsPanel.getFxNode()));
            return Unit.INSTANCE;
          },
            () -> Unit.INSTANCE
          ),
          new PropertiesDialogTabProvider(
            tabPane -> {
              tabPane.getTabs().add(new Tab(language.getText("assignments"), myAssignmentsPanel.getFxComponent()));
              return Unit.INSTANCE;
            },
            () -> {
              myAssignmentsPanel.requestFocus();
              return Unit.INSTANCE;
            }
          )
        ),
        dialogController -> {
          return Unit.INSTANCE;
        }
      );
    }
  }

  private void constructAssignmentsPanel() {
    myAssignmentsPanel = new ResourceAssignmentsPanelFx(person, myTaskManager);
  }

  private void okButtonActionPerformed() {
    if (person.getId() != -1) {
      // person ID is -1 when it is new one
      // i.e. before the Person dialog is closed
      myUIFacade.getUndoManager().undoableEdit("Resource properties changed", this::applyChanges);
    } else {
      myUIFacade.getUndoManager().undoableEdit(GanttLanguage.getInstance().formatText("resource.new.description"), () -> {
        applyChanges();
        myResourceManager.add(person);
//        myUIFacade.getResourceTree().setSelected(person, true);
        myUIFacade.getViewManager().getView(String.valueOf(UIFacade.RESOURCES_INDEX)).setActive(true);
      });
    }
  }

  private void applyChanges() {
    mainPropertiesPanel.save();
    customColumnsPanel.save(customPropertyHolder -> {
      // intentionally do nothing, as customPropertyHolder is already a resource being edited
      return null;
    });
    // [fork change] AFTER the custom columns, and that order is load-bearing. Both can touch the
    // "work week" column: the custom columns tab writes back whatever its text field held when the
    // dialog opened, this panel writes what a button press built. The later write wins, so a button
    // press is not undone by the stale text the other tab is carrying.
    //
    // Writes NOTHING when no button was pressed — that guarantee lives in WorkWeekPanelFx.save(),
    // and it is what keeps the Mon-Fr preselection from becoming an entry for every person whose
    // properties anybody ever opened.
    workWeekPanel.save();
    // [fork change] AFTER the custom columns for the same reason as the working week above: that
    // tab writes back whatever its text fields held when the dialog opened, and the later write
    // has to be the one a button press built.
    //
    // Writes NOTHING when no button was pressed — separately per half, so entering a period does
    // not also write an empty weekly pattern. The guarantee lives in HomeOfficePanelFx.save(), and
    // it is what keeps an untouched person from bringing the two columns into a project that has
    // none.
    homeOfficePanel.save();

    person.clearDaysOff();
    for (DateInterval interval : myDaysOffModel.getIntervals()) {
      person.addDaysOff(new GanttDaysOff(interval.getStart(), interval.getEnd()));
    }
    myAssignmentsPanel.commit();
  }

  private DefaultDateIntervalModel myDaysOffModel;

  private void constructDaysOffPanel() {
    myDaysOffModel = new DefaultDateIntervalModel() {
      @Override
      public int getMaxIntervalLength() {
        return 2;
      }

      @Override
      public void add(DateInterval interval) {
        super.add(interval);
      }

      @Override
      public void remove(DateInterval interval) {
        super.remove(interval);
      }
    };
    DefaultListModel<GanttDaysOff> daysOff = person.getDaysOff();
    for (int i = 0; i < daysOff.getSize(); i++) {
      GanttDaysOff next = daysOff.get(i);
      myDaysOffModel.add(DateInterval.Companion.createFromModelDates(next.getStart().getTime(),
          next.getFinish().getTime()));
    }
  }
}
