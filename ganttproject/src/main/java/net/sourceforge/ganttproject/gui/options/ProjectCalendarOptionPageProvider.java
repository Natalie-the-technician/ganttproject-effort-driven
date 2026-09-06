/*
GanttProject is an opensource project management tool. License: GPL3
Copyright (C) 2011 Dmitry Barashev

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
package net.sourceforge.ganttproject.gui.options;

import biz.ganttproject.core.option.DefaultDateOption;
import biz.ganttproject.core.option.GPOptionGroup;
import biz.ganttproject.core.time.CalendarFactory;
import biz.ganttproject.core.time.TimeDuration;
import biz.ganttproject.customproperty.CustomPropertyManager;
import com.google.common.collect.Lists;
import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.fork.HolidayWorkKt;
import net.sourceforge.ganttproject.gui.UIUtil;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.task.TaskManager;
import net.sourceforge.ganttproject.task.dependency.TaskDependencyException;
import net.sourceforge.ganttproject.task.Task;
import net.sourceforge.ganttproject.task.TaskContainmentHierarchyFacade;
import net.sourceforge.ganttproject.task.algorithm.AlgorithmException;
import net.sourceforge.ganttproject.task.algorithm.ShiftTaskTreeAlgorithm;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.text.MessageFormat;
import java.util.Date;
import java.util.List;

/**
 * Provides project calendar settings page in the settings dialog.
 *
 * @author Dmitry Barashev
 */
public class ProjectCalendarOptionPageProvider extends OptionPageProviderBase {
  private WeekendsSettingsPanel myWeekendsPanel;
  /**
   * [fork change] A4 -- the project setting "allow work on holidays".
   *
   * ON THE PROJECT CALENDAR PAGE and not in the program's options, decided on 03.09.2026: the
   * setting changes computed dates, so in the program's options the same .gan would produce two
   * different plans on two machines and neither would be wrong in any way anybody could point at.
   * It travels in the file, and this page is the one that edits what travels in the file about
   * days.
   */
  private JCheckBox myAllowHolidayWork;
  private DefaultDateOption myProjectStartOption;
  private JRadioButton myMoveAllTasks;
  private JRadioButton myMoveStartingTasks;
  private JLabel myMoveDurationLabel;
  private JPanel myMoveStrategyPanelWrapper;
  private Date myProjectStart;

  public ProjectCalendarOptionPageProvider() {
    super("project.calendar");
  }

  @Override
  public GPOptionGroup[] getOptionGroups() {
    return new GPOptionGroup[0];
  }

  @Override
  public boolean hasCustomComponent() {
    return true;
  }

  @Override
  public Component buildPageComponent() {
    final GanttLanguage i18n = GanttLanguage.getInstance();
    final Box result = Box.createVerticalBox();

    myWeekendsPanel = new WeekendsSettingsPanel(getProject(), getUiFacade());
    myWeekendsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    myWeekendsPanel.initialize();
    result.add(myWeekendsPanel);

    result.add(Box.createVerticalStrut(15));

    // [fork change] A4. Right below the weekend settings, because the two are the only things on
    // this page that say which days may be worked -- and directly beside them so that the
    // difference is visible: the boxes above are about WEEKDAYS, this one is about the calendar
    // events. The switch never touches a weekend.
    myAllowHolidayWork = new JCheckBox(
        ForkI18nKt.forkText("fork.projectCalendar.allowHolidayWork"));
    myAllowHolidayWork.setAlignmentX(Component.LEFT_ALIGNMENT);
    myAllowHolidayWork.setSelected(isHolidayWorkAllowed());
    Box holidayBox = Box.createVerticalBox();
    holidayBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    holidayBox.add(myAllowHolidayWork);
    JLabel holidayHint =
        new JLabel(ForkI18nKt.forkText("fork.projectCalendar.allowHolidayWork.hint"));
    holidayHint.setAlignmentX(Component.LEFT_ALIGNMENT);
    holidayHint.setFont(holidayHint.getFont().deriveFont(Font.PLAIN, holidayHint.getFont().getSize() - 1f));
    holidayBox.add(holidayHint);
    result.add(holidayBox);

    result.add(Box.createVerticalStrut(15));

    myProjectStart = getProject().getTaskManager().getProjectStart();
    myProjectStartOption = new DefaultDateOption("project.startDate", myProjectStart) {
      private TimeDuration getMoveDuration() {
        return getProject().getTaskManager().createLength(getProject().getTimeUnitStack().getDefaultTimeUnit(),
            getInitialValue(), getValue());
      }

      @Override
      public void setValue(Date value) {
        super.setValue(value);
        TimeDuration moveDuration = getMoveDuration();
        if (moveDuration.getLength() != 0) {
          updateMoveOptions(moveDuration);
        }
      }

      @Override
      public void commit() {
        super.commit();
        if (!isChanged()) {
          return;
        }
        try {
          moveProject(getMoveDuration());
        } catch (AlgorithmException e) {
          getUiFacade().showErrorDialog(e);
        }
      }
    };

    Box myMoveOptionsPanel = Box.createVerticalBox();
    myMoveOptionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

    Box dateComponent = Box.createHorizontalBox();
    OptionsPageBuilder builder = new OptionsPageBuilder();
    dateComponent.add(new JLabel(i18n.getText(builder.getI18N().getCanonicalOptionLabelKey(myProjectStartOption))));
    dateComponent.add(Box.createHorizontalStrut(3));
    dateComponent.add(builder.createDateComponent(myProjectStartOption));
    dateComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
    myMoveOptionsPanel.add(dateComponent);
    myMoveOptionsPanel.add(Box.createVerticalStrut(5));

    myMoveStrategyPanelWrapper = new JPanel(new BorderLayout()) {
      @Override
      public void paint(Graphics g) {
        if (isEnabled()) {
          super.paint(g);
          return;
        }
        final BufferedImage buf = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        super.paint(buf.getGraphics());
        final float[] my_kernel = { 0.0625f, 0.125f, 0.0625f, 0.125f, 0.25f, 0.125f, 0.0625f, 0.125f, 0.0625f };
        final ConvolveOp op = new ConvolveOp(new Kernel(3, 3, my_kernel), ConvolveOp.EDGE_NO_OP, null);
        Image img = op.filter(buf, null);
        g.drawImage(img, 0, 0, null);
      }
    };
    myMoveStrategyPanelWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

    myMoveAllTasks = new JRadioButton(i18n.getText("project.calendar.moveAll.label"));
    myMoveAllTasks.setAlignmentX(Component.LEFT_ALIGNMENT);

    myMoveStartingTasks = new JRadioButton(MessageFormat.format(i18n.getText("project.calendar.moveSome.label"),
        i18n.formatDate(CalendarFactory.createGanttCalendar(myProjectStart))));
    myMoveStartingTasks.setAlignmentX(Component.LEFT_ALIGNMENT);

    ButtonGroup moveGroup = new ButtonGroup();
    moveGroup.add(myMoveAllTasks);
    moveGroup.add(myMoveStartingTasks);
    moveGroup.setSelected(myMoveAllTasks.getModel(), true);

    Box moveStrategyPanel = Box.createVerticalBox();
    myMoveDurationLabel = new JLabel();
    myMoveDurationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    moveStrategyPanel.add(myMoveDurationLabel);
    moveStrategyPanel.add(myMoveAllTasks);
    moveStrategyPanel.add(myMoveStartingTasks);

    myMoveStrategyPanelWrapper.add(moveStrategyPanel, BorderLayout.CENTER);
    myMoveOptionsPanel.add(Box.createVerticalStrut(3));
    myMoveOptionsPanel.add(myMoveStrategyPanelWrapper);

    UIUtil.createTitle(myMoveOptionsPanel, i18n.getText("project.calendar.move.title"));
    result.add(myMoveOptionsPanel);

    updateMoveOptions(getProject().getTaskManager().createLength(0));
    return OptionPageProviderBase.wrapContentComponent(result, getCanonicalPageTitle(), null);
  }

  protected void updateMoveOptions(TimeDuration moveDuration) {
    if (moveDuration.getLength() != 0) {
      String moveLabel = MessageFormat.format(
          GanttLanguage.getInstance().getText("project.calendar.moveDuration.label"), moveDuration.getLength(),
          getProject().getTimeUnitStack().encode(moveDuration.getTimeUnit()));
      myMoveDurationLabel.setText(moveLabel);
      UIUtil.setEnabledTree(myMoveStrategyPanelWrapper, true);
    } else {
      UIUtil.setEnabledTree(myMoveStrategyPanelWrapper, false);
    }
  }

  private List<Task> buildMoveScope() {
    var taskManager = getProject().getTaskManager();
    var result = Lists.<Task>newArrayList();
    if (myMoveAllTasks.isSelected()) {
      result.add(taskManager.getRootTask());
    } else if (myMoveStartingTasks.isSelected()) {
      TaskContainmentHierarchyFacade taskTree = taskManager.getTaskHierarchy();
      for (Task t : taskManager.getTasks()) {
        if (t.getStart().getTime().equals(myProjectStart) && !taskTree.hasNestedTasks(t)) {
          result.add(t);
        }
      }
    }
    return result;
  }
  protected void moveProject(TimeDuration moveDuration) throws AlgorithmException {
    var shiftTaskTreeAlgorithm = new ShiftTaskTreeAlgorithm(getProject().getTaskManager(), buildMoveScope(), !myMoveStartingTasks.isSelected());
    shiftTaskTreeAlgorithm.run(moveDuration);
    shiftTaskTreeAlgorithm.commit();
  }

  @Override
  public void commit() {
    myWeekendsPanel.applyChanges(false);
    commitHolidayWork();
    myProjectStartOption.commit();
  }

  /** [fork change] A4. The resource property manager is where the setting lives; see HolidayWork.kt. */
  private CustomPropertyManager resourceProperties() {
    return getProject() == null ? null : getProject().getResourceCustomPropertyManager();
  }

  private boolean isHolidayWorkAllowed() {
    CustomPropertyManager properties = resourceProperties();
    return properties != null && HolidayWorkKt.allowsHolidayWork(properties);
  }

  /**
   * [fork change] A4. Writes the setting and RE-RUNS THE SCHEDULE, the same two steps
   * WeekendsSettingsPanel takes when a weekday changes -- and for the same reason: the dates on
   * screen were computed on the old day grid and would otherwise stay there until the next edit.
   *
   * NOTHING IS WRITTEN WHEN NOTHING CHANGED, so opening the page and closing it again does not put
   * a custom property definition into a file that never had one.
   */
  private void commitHolidayWork() {
    CustomPropertyManager properties = resourceProperties();
    if (properties == null || myAllowHolidayWork == null) {
      return;
    }
    boolean wanted = myAllowHolidayWork.isSelected();
    if (wanted == HolidayWorkKt.allowsHolidayWork(properties)) {
      return;
    }
    HolidayWorkKt.setAllowHolidayWork(properties, wanted);
    GPLogger.log("[fork change] Project setting 'allow work on holidays' is now "
        + (wanted ? "ON: public holidays are no longer counted as days off, and the working week "
                    + "of each person decides alone. Weekends are not affected."
                  : "OFF: a public holiday is a day off again, as it is by default.")
        + " Recomputing the schedule.");
    try {
      TaskManager taskManager = getProject().getTaskManager();
      taskManager.getAlgorithmCollection().getRecalculateTaskScheduleAlgorithm().run();
      taskManager.getAlgorithmCollection().getAdjustTaskBoundsAlgorithm()
          .adjustNestedTasks(taskManager.getRootTask());
    } catch (TaskDependencyException e) {
      GPLogger.log(e);
    }
  }
}
