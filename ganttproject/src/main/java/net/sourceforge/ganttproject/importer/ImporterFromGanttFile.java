/*
GanttProject is an opensource project management tool.
Copyright (C) 2010-2011 Dmitry Barashev, GanttProject team

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
package net.sourceforge.ganttproject.importer;

import biz.ganttproject.core.calendar.ImportCalendarOption;
import biz.ganttproject.core.option.ChangeValueEvent;
import biz.ganttproject.core.option.ChangeValueListener;
import biz.ganttproject.core.option.GPOption;
import biz.ganttproject.core.table.ColumnList;
import net.sourceforge.ganttproject.IGanttProject;
import net.sourceforge.ganttproject.document.Document;
import net.sourceforge.ganttproject.document.FileDocument;
import net.sourceforge.ganttproject.fork.BaselineCollisionAnswer;
import net.sourceforge.ganttproject.fork.BaselineNameCollision;
import net.sourceforge.ganttproject.fork.BaselineNameCollisionDialogKt;
import net.sourceforge.ganttproject.gui.UIFacade;
import net.sourceforge.ganttproject.resource.HumanResourceMerger;
import net.sourceforge.ganttproject.resource.MergeResourcesEnum;
import net.sourceforge.ganttproject.task.algorithm.AlgorithmCollection;
import org.osgi.service.prefs.Preferences;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.sourceforge.ganttproject.fork.BaselineNameCollisionQuestionKt.baselineNameCollisions;
import static net.sourceforge.ganttproject.importer.BufferProjectImportKt.importBufferProject;
import static net.sourceforge.ganttproject.importer.AskingImportCalendarOptionKt.unansweredCalendarQuestionText;

public class ImporterFromGanttFile extends ImporterBase {
  private final HumanResourceMerger.MergeResourcesOption myMergeResourcesOption = new HumanResourceMerger.MergeResourcesOption();
  private final ImportCalendarOption myImportCalendarOption = new AskingImportCalendarOption();

  // A FIELD, NOT A DIRECT CALL, so a test can replace it with a fake answer instead of opening a
  // real window -- the same reason myImportCalendarOption is a field reachable by reflection in
  // ImportCalendarQuestionTest. The production value is the real, blocking modal dialog.
  private java.util.function.Function<List<String>, BaselineCollisionAnswer> myBaselineCollisionAsk =
      BaselineNameCollisionDialogKt::askBaselineNameCollisionBlocking;

  private final GPOption[] myOptions = new GPOption[]{myMergeResourcesOption, myImportCalendarOption};

  public ImporterFromGanttFile() {
    super("ganttprojectFiles");
    // NO PRE-SELECTED CALENDAR ANSWER. This line used to read
    //   myImportCalendarOption.setSelectedValue(ImportCalendarOption.Values.NO);
    // which pre-selected "leave existing": a project with working hours of its own computed
    // differently after an import than before, and nobody was asked and nothing was said.
    // Natalie's decision of 08.10.2026 is to ask every time with NEITHER answer pre-selected, so
    // the starting value is UNDECIDED -- AskingImportCalendarOption carries it, and that is also
    // where the label of that state comes from. Setting it again here would be the one line that
    // quietly undoes the decision, which is why it is written out above instead of deleted.
  }

  @Override
  public String getFileNamePattern() {
    return "xml|gan";
  }

  @Override
  protected GPOption[] getOptions() {
    return myOptions;
  }

  @Override
  public void setContext(IGanttProject project, UIFacade uiFacade, Preferences preferences) {
    super.setContext(project, uiFacade, preferences);
    final Preferences node = preferences.node("/instance/net.sourceforge.ganttproject/import");
    myMergeResourcesOption.lock();
    myMergeResourcesOption.loadPersistentValue(node.get(myMergeResourcesOption.getID(), MergeResourcesEnum.DEFAULT.name().toLowerCase()));
    myMergeResourcesOption.commit();
    myMergeResourcesOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        node.put(myMergeResourcesOption.getID(), String.valueOf(event.getNewValue()));
      }
    });
    // ANSWERING THE CALENDAR QUESTION HAS TO WAKE THE WIZARD UP. The finish button is set from
    // canFinish() in the wizard's refresh, and that refresh only runs when needsRefresh is raised.
    // Without this listener the button would stay disabled after the user picked an answer -- the
    // question would be unanswerable instead of merely unanswered.
    //
    // NOT PERSISTED, unlike the merge option above, and that is the point: a stored answer would
    // be a pre-selection again on the next import, which is exactly what Natalie decided against.
    myImportCalendarOption.addChangeValueListener(new ChangeValueListener() {
      @Override
      public void changeValue(ChangeValueEvent event) {
        ImporterWizardModel model = getModel();
        if (model != null) {
          model.getNeedsRefresh().set(true, ImporterFromGanttFile.this);
        }
      }
    });
  }

  /**
   * The calendar question, while it has no answer.
   *
   * <p>ADDED IN THIS FORK. The wizard keeps its finish button disabled while this is non-null.
   */
  @Override
  public String getUnansweredQuestion() {
    return myImportCalendarOption.isUndecided() ? unansweredCalendarQuestionText() : null;
  }

  @Override
  public void run() {
    // THE SECOND LOCK, AND THE ONE THAT MAKES "NOTHING HAPPENS" TRUE. It sits BEFORE the
    // undoableEdit and before the file is read, so an unanswered calendar question leaves the
    // target project exactly as it was: no calendar taken over, no calendar discarded, no tasks,
    // no resources, not even an empty entry in the undo stack. The wizard's disabled button is
    // the first lock; this one holds for any caller that does not go through the wizard.
    if (myImportCalendarOption.isUndecided()) {
      getUiFacade().showErrorDialog(unansweredCalendarQuestionText());
      return;
    }
    final IGanttProject targetProject = getProject();

    // READ EVERY FILE AND RESOLVE ITS BASELINE QUESTION *BEFORE* OPENING THE UNDO TRANSACTION.
    // ADDED IN THIS FORK, for the same reason the calendar guard above sits before
    // getUndoManager() and not inside its Runnable: UndoManagerImpl.undoableEdit() registers an
    // undo-stack entry UNCONDITIONALLY, before its Runnable even starts -- so a baseline question
    // left unanswered INSIDE that Runnable would still have left an edit (an empty one, but one)
    // on the stack. Resolving every file's question out here means an unanswered one can `return`
    // before undoableEdit is ever called, which is what "der Import geht nicht weiter" has to mean
    // for the baseline question too, not only for the calendar one.
    //
    // A FILE THAT FAILS TO READ IS SKIPPED, NOT FATAL -- same as the per-file try/catch this
    // replaces: one bad file does not stop the others, it is reported and left out.
    List<PreparedImport> prepared = new ArrayList<>();
    for (File file : getModel().getFiles()) {
      BufferProject bufferProject = createBufferProject(targetProject, getUiFacade());
      try {
        readDocument(file, bufferProject);
      } catch (Exception e) {
        getUiFacade().showErrorDialog(e);
        continue;
      }

      // THE BASELINE-NAME QUESTION, ADDED IN THIS FORK. "einmal fragen, für alle" (Natalie,
      // 10.10.2026): collisions are collected ONCE for the whole file, not per baseline, and the
      // dialog -- if there is anything to ask about at all -- falls at most once per file.
      // bufferProject.getBaselines() is only known from here on, after readDocument() above; that
      // is why this cannot be a wizard-page option decided ahead of time like the calendar
      // question is.
      List<String> collidingBaselineNames = baselineNameCollisions(bufferProject.getBaselines(), targetProject.getBaselines());
      BaselineNameCollision baselineNameCollision;
      if (collidingBaselineNames.isEmpty()) {
        // Nothing collides, so nothing is asked, and the value below is never read by
        // importBaselines() for any baseline -- see the "!collides" branch there.
        baselineNameCollision = BaselineNameCollision.APPEND_AS_IS;
      } else {
        BaselineCollisionAnswer answer = myBaselineCollisionAsk.apply(collidingBaselineNames);
        if (answer == null) {
          // OHNE ANTWORT PASSIERT NICHTS: the dialog was closed without a choice. Nothing of
          // THIS IMPORT -- not this file's baselines, not this file's tasks, not any OTHER file
          // already queued in `prepared` -- is written to the target project: undoableEdit below
          // is simply never reached.
          return;
        }
        baselineNameCollision = answer.getPolicy();
      }
      prepared.add(new PreparedImport(bufferProject, baselineNameCollision));
    }

    if (prepared.isEmpty()) {
      return;
    }
    getUiFacade().getUndoManager().undoableEdit("Import", new Runnable() {
      @Override
      public void run() {
        for (PreparedImport p : prepared) {
          try {
            importBufferProject(targetProject, p.bufferProject, BufferProjectImportKt.asImportBufferProjectApi(getUiFacade()),
                myMergeResourcesOption, myImportCalendarOption, false, p.baselineNameCollision);
          } catch (Exception e) {
            getUiFacade().showErrorDialog(e);
          }
        }
      }
    });
  }

  /** One file, already parsed and with its baseline question (if any) already resolved. */
  private static class PreparedImport {
    final BufferProject bufferProject;
    final BaselineNameCollision baselineNameCollision;

    PreparedImport(BufferProject bufferProject, BaselineNameCollision baselineNameCollision) {
      this.bufferProject = bufferProject;
      this.baselineNameCollision = baselineNameCollision;
    }
  }

  /** Parses {@code file} into {@code bufferProject}. Does not touch the target project. */
  private void readDocument(File file, BufferProject bufferProject) throws Document.DocumentException, IOException {
    Document document = bufferProject.getDocumentManager().getDocument(file.getAbsolutePath());
    AlgorithmCollection algs = getProject().getTaskManager().getAlgorithmCollection();
    try {
      algs.getScheduler().setEnabled(false);
      algs.getRecalculateTaskScheduleAlgorithm().setEnabled(false);
      algs.getAdjustTaskBoundsAlgorithm().setEnabled(false);
      document.read();
    } finally {
      algs.getRecalculateTaskScheduleAlgorithm().setEnabled(true);
      algs.getAdjustTaskBoundsAlgorithm().setEnabled(true);
      algs.getScheduler().setEnabled(true);
    }
  }

  private static class TaskFieldImpl implements ColumnList.Column {
    private final String myID;
    private final int myOrder;
    private final int myWidth;

    TaskFieldImpl(String id, int order, int width) {
      myID = id;
      myOrder = order;
      myWidth = width;
    }

    @Override
    public SortOrder getSort() {
      return SortOrder.UNSORTED;
    }

    @Override
    public void setSort(SortOrder sort) {

    }

    @Override
    public String getID() {
      return myID;
    }

    @Override
    public int getOrder() {
      return myOrder;
    }

    @Override
    public int getWidth() {
      return myWidth;
    }

    @Override
    public boolean isVisible() {
      return true;
    }

    @Override
    public String getName() {
      return null;
    }

    @Override
    public void setVisible(boolean visible) {
    }

    @Override
    public void setOrder(int order) {
    }

    @Override
    public void setWidth(int width) {
    }
  }

  public static class VisibleFieldsImpl implements ColumnList {
    private final List<Column> myFields = new ArrayList<Column>();

    @Override
    public void add(String name, int order, int width) {
      myFields.add(new TaskFieldImpl(name, order, width));
    }

    @Override
    public void clear() {
      myFields.clear();
    }

    @Override
    public Column getField(int index) {
      return myFields.get(index);
    }

    @Override
    public int getSize() {
      return myFields.size();
    }

    @Override
    public void importData(ColumnList source, boolean keepVisibleColumnsIgnored) {
      for (int i = 0; i < source.getSize(); i++) {
        Column nextField = source.getField(i);
        myFields.add(nextField);
      }
    }

    @Override
    public List<Column> exportData() {
      return List.copyOf(myFields);
    }
  }

  private BufferProject createBufferProject(final IGanttProject targetProject, final UIFacade uiFacade) {
    return new BufferProject(targetProject, uiFacade);
  }

  protected Document getDocument(File selectedFile) {
    return new FileDocument(selectedFile);
  }

}
