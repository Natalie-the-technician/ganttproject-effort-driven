/*
Copyright 2026 BarD Software s.r.o

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
package biz.ganttproject.impex.csv;

import biz.ganttproject.app.DefaultLocalizer;
import biz.ganttproject.app.DummyLocalizer;
import biz.ganttproject.app.InternationalizationCoreKt;
import biz.ganttproject.core.model.task.TaskDefaultColumn;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import javafx.beans.property.SimpleObjectProperty;
import net.sourceforge.ganttproject.ResourceDefaultColumn;
import net.sourceforge.ganttproject.TestSetupHelper;
import net.sourceforge.ganttproject.TestSetupHelper.TaskManagerBuilder;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.resource.HumanResourceMerger.MergeResourcesOption;
import net.sourceforge.ganttproject.resource.OverwritingMerger;
import net.sourceforge.ganttproject.roles.RoleManagerImpl;
import net.sourceforge.ganttproject.task.Task;
import net.sourceforge.ganttproject.task.TaskManager;
import net.sourceforge.ganttproject.test.task.TaskTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * {@code ImporterFromCsvFile} reads the file into a buffer project and then hands the buffer over
 * to {@code importBufferProject} together with a freshly built {@code MergeResourcesOption} whose
 * value it never sets. The default value of that option therefore decides how duplicate resources
 * are detected on the CSV path, exactly as it does on the GanttProject-file path.
 *
 * The resource ID is a mandatory column of the resource section of a CSV file, and GanttProject
 * numbers resource IDs from 0 in every file it exports. Two files which were created independently
 * therefore collide on resource #0 by construction. With the duplicate detection defaulting to
 * "by ID", importing such a file replaced a resource of the *target* project with an imported one.
 *
 * These tests go through {@code GanttCSVOpen} -- the CSV-specific half of the import -- and then
 * merge with a freshly built option, that is, with the *default*.
 */
public class CsvImportResourceMergeTest extends TaskTestCase {

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TaskDefaultColumn.setLocaleApi(key -> GanttLanguage.getInstance().getText(key));
    InternationalizationCoreKt.setRootLocalizer(new DefaultLocalizer("", () -> DummyLocalizer.INSTANCE, null,
        new SimpleObjectProperty(null)) {
      @Nullable
      @Override
      public String formatTextOrNull(@NotNull String key, @NotNull Object... args) {
        return key;
      }
    });
    GanttLanguage.getInstance().setShortDateFormat(new SimpleDateFormat("dd/MM/yy"));
  }

  private static Supplier<InputStream> supplierOf(String... lines) {
    final byte[] data = Joiner.on('\n').join(lines).getBytes(Charsets.UTF_8);
    return () -> new ByteArrayInputStream(data);
  }

  private static String resourceHeader(ResourceDefaultColumn... fields) {
    return Joiner.on(',').join(Stream.of(fields).map(ResourceDefaultColumn::toString).iterator());
  }

  private static String taskHeader(TaskRecords.TaskFields... fields) {
    return Joiner.on(',').join(Stream.of(fields).map(TaskRecords.TaskFields::toString).iterator());
  }

  /**
   * Reads a CSV file the way {@code ImporterFromCsvFile} does: into the resource manager of a
   * buffer project, which is a plain {@code HumanResourceManager} of its own.
   */
  private HumanResourceManager readCsvIntoBufferProject(Supplier<InputStream> csv) throws Exception {
    TaskManagerBuilder bufferBuilder = TestSetupHelper.newTaskManagerBuilder();
    TaskManager bufferTaskManager = bufferBuilder.build();
    HumanResourceManager bufferResources = bufferBuilder.getResourceManager();
    new GanttCSVOpen(csv, SpreadsheetFormat.CSV, bufferTaskManager, bufferResources,
        new RoleManagerImpl(), bufferBuilder.getTimeUnitStack()).load();
    return bufferResources;
  }

  private static Supplier<InputStream> csvOfOneStranger() {
    String taskHeader = taskHeader(TaskRecords.TaskFields.NAME, TaskRecords.TaskFields.BEGIN_DATE,
        TaskRecords.TaskFields.END_DATE);
    String resourceHeader = resourceHeader(ResourceDefaultColumn.NAME, ResourceDefaultColumn.ID,
        ResourceDefaultColumn.EMAIL, ResourceDefaultColumn.STANDARD_RATE);
    return supplierOf(
        taskHeader,
        "Their task,23/07/12,25/07/12",
        "",
        resourceHeader,
        "John Acquired,0,john@acquired.example,50.0");
  }

  /**
   * The resource which the CSV file describes must be added to the target project, not written over
   * a resource of the target project which happens to be resource #0 there as well.
   */
  public void testCsvImportDoesNotOverwriteResourceWithTheSameId() throws Exception {
    TaskManagerBuilder targetBuilder = TestSetupHelper.newTaskManagerBuilder();
    setTaskManager(targetBuilder.build());
    HumanResourceManager target = targetBuilder.getResourceManager();
    HumanResource jane = new HumanResource("Jane Target", 0, target);
    jane.setMail("jane@target.example");
    jane.setStandardPayRate(new BigDecimal("100"));
    target.add(jane);

    HumanResourceManager imported = readCsvIntoBufferProject(csvOfOneStranger());
    assertEquals("Precondition: the CSV file describes exactly one resource",
        1, imported.getResources().size());
    assertEquals("Precondition: the resource read from the CSV file is resource #0, "
        + "just like the resource of the target project",
        0, imported.getResources().get(0).getId());

    target.importData(imported, new OverwritingMerger(new MergeResourcesOption()), Collections.emptyMap());

    assertEquals("The resource read from the CSV file must be added, not merged into the existing one",
        2, target.getResources().size());
    HumanResource survivor = target.getById(0);
    assertNotNull("The resource of the target project has disappeared", survivor);
    assertEquals("The name of the target project's resource was overwritten by the CSV import",
        "Jane Target", survivor.getName());
    assertEquals("The mail address of the target project's resource was overwritten by the CSV import",
        "jane@target.example", survivor.getMail());
    assertEquals("The pay rate of the target project's resource was overwritten by the CSV import",
        100.0, survivor.getStandardPayRate().doubleValue(), 0.0001);
  }

  /**
   * The lasting damage, on the CSV path as well: the cost of a task which belongs to the target
   * project changes, because its assignee now carries a stranger's pay rate.
   */
  public void testCsvImportKeepsTheCostOfTheTargetProjectsOwnTask() throws Exception {
    TaskManagerBuilder targetBuilder = TestSetupHelper.newTaskManagerBuilder();
    setTaskManager(targetBuilder.build());
    HumanResourceManager target = targetBuilder.getResourceManager();
    HumanResource jane = new HumanResource("Jane Target", 0, target);
    jane.setMail("jane@target.example");
    jane.setStandardPayRate(new BigDecimal("100"));
    target.add(jane);

    Task ownTask = createTask();
    ownTask.setDuration(ownTask.getManager().createLength(5));
    ownTask.getAssignmentCollection().addAssignment(jane).setLoad(100f);
    assertEquals("Precondition: 5 days at 100% and a rate of 100",
        500.0, jane.getTotalCost().doubleValue(), 0.0001);

    HumanResourceManager imported = readCsvIntoBufferProject(csvOfOneStranger());

    target.importData(imported, new OverwritingMerger(new MergeResourcesOption()), Collections.emptyMap());

    assertEquals("The cost of the target project's own task changed because of the CSV import",
        500.0, jane.getTotalCost().doubleValue(), 0.0001);
    HumanResource assignee = ownTask.getAssignments()[0].getResource();
    assertEquals("The task of the target project is assigned to someone else after the CSV import",
        "Jane Target", assignee.getName());
  }

  /**
   * The point of the option is not lost on the CSV path either: when the file really does describe
   * a person who is already in the target project, the records are still merged, and the differing
   * resource IDs do not get in the way.
   */
  public void testCsvImportStillMergesTheSamePerson() throws Exception {
    TaskManagerBuilder targetBuilder = TestSetupHelper.newTaskManagerBuilder();
    setTaskManager(targetBuilder.build());
    HumanResourceManager target = targetBuilder.getResourceManager();
    HumanResource jane = new HumanResource("Jane Target", 0, target);
    jane.setMail("jane@target.example");
    jane.setStandardPayRate(new BigDecimal("100"));
    target.add(jane);

    String taskHeader = taskHeader(TaskRecords.TaskFields.NAME, TaskRecords.TaskFields.BEGIN_DATE,
        TaskRecords.TaskFields.END_DATE);
    String resourceHeader = resourceHeader(ResourceDefaultColumn.NAME, ResourceDefaultColumn.ID,
        ResourceDefaultColumn.EMAIL, ResourceDefaultColumn.STANDARD_RATE);
    HumanResourceManager imported = readCsvIntoBufferProject(supplierOf(
        taskHeader,
        "Their task,23/07/12,25/07/12",
        "",
        resourceHeader,
        "Jane Target,7,jane@elsewhere.example,120.0"));

    target.importData(imported, new OverwritingMerger(new MergeResourcesOption()), Collections.emptyMap());

    assertEquals("The same person must not be duplicated by the CSV import",
        1, target.getResources().size());
    assertEquals("Jane Target", target.getResources().get(0).getName());
  }
}
