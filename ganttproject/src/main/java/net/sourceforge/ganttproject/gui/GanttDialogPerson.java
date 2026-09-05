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
import net.sourceforge.ganttproject.fork.DaysOffDurationKt;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.fork.HomeOfficePanelFx;
import net.sourceforge.ganttproject.fork.VacationPreview;
import net.sourceforge.ganttproject.fork.VacationPreviewKt;
import net.sourceforge.ganttproject.fork.WorkWeekPanelFx;
import net.sourceforge.ganttproject.gui.NotificationChannel;
import net.sourceforge.ganttproject.gui.resourceproperties.MainPropertiesPanel;
import net.sourceforge.ganttproject.gui.resourceproperties.ResourceAssignmentsPanelFx;
import net.sourceforge.ganttproject.gui.taskproperties.CustomColumnsPanel;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.storage.ProjectDatabase;
import net.sourceforge.ganttproject.task.TaskManager;

import kotlin.Pair;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.time.LocalDate;
import java.util.List;

public class GanttDialogPerson {
  private static final GanttLanguage language = GanttLanguage.getInstance();

  private final TaskManager myTaskManager;
  private final HumanResourceManager myResourceManager;
  /**
   * [fork change] KEPT IN A FIELD since the absence preview, where it was only ever handed to the
   * panels before. The preview needs it for the same reason levelling does: the hours per day, the
   * utilisation and the working weeks all hang off the resource columns, and a forecast computed
   * without them is a forecast about somebody else.
   */
  private final CustomPropertyManager myResourceProperties;
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
    myResourceProperties = customPropertyManager;
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
    // [fork change] READ BEFORE ANYTHING IS WRITTEN. The forecast below compares the plan with
    // this person's absences against the plan without them, and by the time applyChanges() has run
    // the old state is gone. The list daysOffRanges builds is a fresh one, so it survives the
    // write below unchanged.
    List<Pair<LocalDate, LocalDate>> absencesBefore = DaysOffDurationKt.daysOffRanges(person);
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
    previewWhatTheAbsenceCosts(absencesBefore);
  }

  /**
   * [fork change] WHAT THIS PERSON'S ABSENCE COSTS THE PLAN — said AFTER the Ok, as a report.
   *
   * ═══ WHY AFTER AND NOT BEFORE ═══
   *
   * The measurement of 05.09.2026 left this open on purpose and asked for a recommendation. It is
   * this one, and the reasons are three:
   *
   * <p>ENTERING AN ABSENCE MOVES NOTHING. Not one date in the plan changes when a holiday is
   * saved; the dates move when the workload is levelled, and THAT menu item already asks before it
   * writes, with a preview of its own. A "do you really want this?" here would be asking consent
   * for something that is not happening. What is wanted at this moment is a forecast, and a
   * forecast is a report.
   *
   * <p>OK WRITES MUCH MORE THAN THE ABSENCE. The name, the custom columns, the working week, the
   * home office and the assignments all go into ONE undo step together with the days off. A yes/no
   * that can only answer for the whole step would throw away edits that have nothing to do with
   * the holiday — and it would do it with the dialog already gone: {@code onHide.run()} has run
   * before this method is reached, so there would be nothing left to correct in.
   *
   * <p>AND IT IS REVERSIBLE ANYWAY. The write above is one undoable edit. Ctrl-Z is the "no", and
   * it is a better one than a question, because it can be pressed after reading rather than before.
   *
   * <p>Natalie can still turn this round: what would have to change is the order of the two calls
   * in {@link #okButtonActionPerformed} plus a question dialog in place of the report, and the
   * measuring in {@code VacationPreview.kt} would not be touched at all. The cost is not what
   * decides it either way — the two levelling runs together were measured at about 110 ms on a plan
   * of 236 leaves, which is not perceptible in a dialog.
   *
   * <p>ON THIS THREAD, DELIBERATELY. It reads the task model, and the task model belongs to the
   * event thread; handing the calculation to a background thread to save a tenth of a second would
   * buy a data race with the chart.
   *
   * <p>NOTHING AT ALL WHEN THE ABSENCES DID NOT CHANGE. Somebody who opened this dialog to correct
   * a name gets no box. That is what keeps the report from becoming the thing everybody clicks away
   * without reading.
   */
  private void previewWhatTheAbsenceCosts(List<Pair<LocalDate, LocalDate>> absencesBefore) {
    List<Pair<LocalDate, LocalDate>> absencesNow = DaysOffDurationKt.daysOffRanges(person);
    if (absencesBefore.equals(absencesNow)) {
      return;
    }
    VacationPreview preview = VacationPreviewKt.vacationPreview(
        myTaskManager,
        myResourceManager,
        // The same object GanttProject hands the levelling; see GanttProjectBase.getTaskCustomColumnManager.
        myTaskManager.getCustomPropertyManager(),
        myResourceProperties,
        // BEFORE is the state WITHOUT the absences that were just entered, and it is the
        // hypothetical one. That direction is chosen: everything else this dialog wrote — the
        // working week, the home office — is then in BOTH runs, so the only difference between
        // them is the absence itself.
        DaysOffDurationKt.daysOffReplacedFor(person, absencesBefore),
        DaysOffDurationKt.getDaysOffAsEntered(),
        LocalDate.now());
    if (preview == null) {
      // No forecast can be made: nothing to level, a cycle in the dependencies, or an unreadable
      // hours schedule. The levelling menu item reports all three properly and names the remedy; a
      // resource dialog is the wrong place to teach about dependency cycles.
      return;
    }
    String message = VacationPreviewKt.previewText(preview);
    if (preview.getChangesNothing()) {
      // THE QUIET CASE GETS THE QUIET CHANNEL. An absence that costs nothing is the most frequent
      // one — on one measured shape seventeen of nineteen positions of the holiday — and a modal
      // box saying "nothing happens" is exactly how a preview becomes the thing people learn to
      // dismiss. The sentence is still said; it is said where a reassurance belongs.
      //
      // The notification is built here rather than through showNotificationDialog for the reason
      // GanttProject.java records at its own message sink: that call embeds the text in templates
      // the RSS channel does not have, and would swallow the message.
      NotificationManager manager = myUIFacade.getNotificationManager();
      manager.addNotifications(List.of(manager.createNotification(
          NotificationChannel.RSS,
          ForkI18nKt.forkText("fork.vacation.preview.head"),
          "<p>" + message.replace("\n", "<br>") + "</p>",
          null)));
    } else {
      // A WINDOW AND NOT A NOTIFICATION when there IS something to read. Measured on screen for the
      // estimating-quality report and written up at its call site in GanttProject.java: a
      // multi-line report in the notification area is practically invisible — on the first run it
      // was not found at all.
      myUIFacade.showOptionDialog(
          JOptionPane.INFORMATION_MESSAGE,
          message,
          new Action[] {OkAction.create("ok", () -> Unit.INSTANCE)});
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
