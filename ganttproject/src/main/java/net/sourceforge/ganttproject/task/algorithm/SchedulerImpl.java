/*
Copyright 2012 GanttProject Team

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
package net.sourceforge.ganttproject.task.algorithm;

import biz.ganttproject.core.calendar.GPCalendar;
import biz.ganttproject.core.calendar.GPCalendar.DayMask;
import biz.ganttproject.core.calendar.GPCalendarCalc;
import biz.ganttproject.core.time.CalendarFactory;
import biz.ganttproject.core.time.GanttCalendar;
import biz.ganttproject.core.time.TimeDuration;
import biz.ganttproject.core.time.TimeUnit;
import com.google.common.collect.BoundType;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.task.Task;
import net.sourceforge.ganttproject.task.TaskContainmentHierarchyFacade;
import net.sourceforge.ganttproject.task.TaskImpl;
import net.sourceforge.ganttproject.task.TaskMutator;
import net.sourceforge.ganttproject.task.algorithm.DependencyGraph.DependencyEdge;
import net.sourceforge.ganttproject.task.algorithm.DependencyGraph.ImplicitSubSuperTaskDependency;
import net.sourceforge.ganttproject.task.algorithm.DependencyGraph.Node;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * This class walk the dependency graph and updates start and end dates of tasks
 * according to information returned by dependency edges.
 *
 * @author dbarashev
 */
public class SchedulerImpl extends AlgorithmBase {
  private final DependencyGraph myGraph;
  private boolean isRunning;
  private final Supplier<TaskContainmentHierarchyFacade> myTaskHierarchy;

  /**
   * [fork change] Derives the duration of a task the scheduler is about to place.
   *
   * The scheduler carries the dependency graph and the hierarchy and nothing else; effort and the
   * daily availability of the people hang off the CustomPropertyManager, which lives on the other
   * side of the model. Rather than teach this class about resources, it takes the derivation as a
   * callback. It is a plain BiConsumer on purpose: no new type, no fork import in this file.
   *
   * The default does nothing, so the two-argument constructor -- the one the tests use -- behaves
   * exactly as before.
   */
  private final BiConsumer<Task, Date> myDurationDerivation;

  /** [fork change] Set by modifyTaskStart/modifyTaskEnd when they really change something. */
  private boolean myTaskChanged;

  /**
   * [fork change] Upper bound for the passes in {@link #doRun()}.
   *
   * MEASURED: with finish-finish dependencies three passes are needed to reach a fixpoint, and
   * that number does not depend on the order in which the nodes are visited. Mixed dependency
   * types can produce configurations with no fixpoint at all, which is why a bound is mandatory
   * rather than a nicety.
   */
  static final int MAX_PASSES = 3;

  public SchedulerImpl(DependencyGraph graph, Supplier<TaskContainmentHierarchyFacade> taskHierarchy) {
    this(graph, taskHierarchy, (task, plannedStart) -> { });
  }

  /** [fork change] The three-argument form: same scheduler, plus the duration derivation. */
  public SchedulerImpl(DependencyGraph graph, Supplier<TaskContainmentHierarchyFacade> taskHierarchy,
                       BiConsumer<Task, Date> durationDerivation) {
    myGraph = graph;
    myTaskHierarchy = taskHierarchy;
    myDurationDerivation = durationDerivation;
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
  }

  @Override
  public void run() {
    if (!isEnabled() || isRunning) {
      return;
    }
    isRunning = true;
    try {
      doRun();
    } finally {
      isRunning = false;
    }
  }

  private void doRun() {
    // [fork change] Repeat until nothing moves any more, at most MAX_PASSES times.
    //
    // A single walk over the layers was enough as long as the scheduler only shifted start dates.
    // Once the duration is derived while placing a task, the end moves too, and with a
    // finish-finish dependency that feeds back into tasks already visited in this very pass.
    //
    // Reaching the bound is NOT silently accepted: it means the plan has no fixpoint, and whoever
    // is looking at it deserves to be told rather than left with a half-computed schedule.
    for (int pass = 1; pass <= MAX_PASSES; pass++) {
      myTaskChanged = false;
      onePass();
      if (!myTaskChanged) {
        return;
      }
    }
    reportUnsettled();
  }

  private void onePass() {
    int layers = myGraph.checkLayerValidity();
    for (int i = 0; i < layers; i++) {
      Collection<Node> layer = myGraph.getLayer(i);
      for (Node node : layer) {
        try {
          schedule(node);
        } catch (IllegalArgumentException e) {
          if (getDiagnostic() != null) {
            getDiagnostic().logError(e);
          } else {
            error(e);
          }
        }
      }
    }
  }

  /**
   * [fork change] Says out loud that the bound was reached.
   *
   * Both bounds this fork already had break off in silence. That is not continued here: the
   * message goes to the log in every case, and additionally to the diagnostic when the caller
   * provided one -- that is the channel the "the following tasks have moved" dialog reads.
   */
  private void reportUnsettled() {
    IllegalStateException ex = new IllegalStateException(
        "Scheduler did not settle within " + MAX_PASSES + " passes: tasks were still moving in the"
            + " last one. The plan may contain a cycle of dependencies that has no fixpoint.");
    GPLogger.create("SchedulerImpl").error(ex.getMessage(), new Object[0], Collections.emptyMap(), ex);
    if (getDiagnostic() != null) {
      getDiagnostic().logError(ex);
    }
  }

  private void schedule(Node node) {
    debug("Scheduling node {}", node);
    Range<Date> startRange = Range.all();
    Range<Date> endRange = Range.all();

    Range<Date> weakStartRange = Range.all();
    Range<Date> weakEndRange = Range.all();

    List<Date> subtaskRanges = Lists.newArrayList();
    List<DependencyEdge> incoming = node.getIncoming();
    debug(".. #incoming edges={}", incoming.size());
    for (DependencyEdge edge : incoming) {
      if (!edge.refresh()) {
        continue;
      }
      if (edge instanceof ImplicitSubSuperTaskDependency) {
        subtaskRanges.add(edge.getStartRange().upperEndpoint());
        subtaskRanges.add(edge.getEndRange().lowerEndpoint());
      } else {
        if (edge.isWeak()) {
          weakStartRange = weakStartRange.intersection(edge.getStartRange());
          weakEndRange = weakEndRange.intersection(edge.getEndRange());
        } else {
          startRange = startRange.intersection(edge.getStartRange());
          endRange = endRange.intersection(edge.getEndRange());
        }
      }
      if (startRange.isEmpty() || endRange.isEmpty()) {
        debug("..both start and end ranges were calculated as empty for task={} Skipping it", node.getTask());
      }
    }
    debug("..Ranges: start={} end={} weakStart={} weakEnd={}", startRange, endRange, weakStartRange, weakEndRange);

    Range<Date> subtasksSpan = subtaskRanges.isEmpty() ?
        Range.closed(node.getTask().getStart().getTime(), node.getTask().getEnd().getTime()) : Range.encloseAll(subtaskRanges);
    Range<Date> subtreeStartUpwards = subtasksSpan.span(Range.downTo(node.getTask().getStart().getTime(), BoundType.CLOSED));
    Range<Date> subtreeEndDownwards = subtasksSpan.span(Range.upTo(node.getTask().getEnd().getTime(), BoundType.CLOSED));
    debug("..Subtasks span={}", subtasksSpan);

    if (!startRange.equals(Range.all())) {
      startRange = startRange.intersection(weakStartRange);
    } else if (!weakStartRange.equals(Range.all())) {
      startRange = weakStartRange.intersection(subtreeStartUpwards);
    }
    if (!endRange.equals(Range.all())) {
      endRange = endRange.intersection(weakEndRange);
    } else if (!weakEndRange.equals(Range.all())) {
      endRange = weakEndRange.intersection(subtreeEndDownwards);
    }
    if (node.getTask().getThirdDateConstraint() == TaskImpl.EARLIESTBEGIN && node.getTask().getThird() != null) {
      startRange = startRange.intersection(Range.downTo(node.getTask().getThird().getTime(), BoundType.CLOSED));
      debug(".. applying earliest start={}. Now start range={}", node.getTask().getThird(), startRange);
    }
    if (!subtaskRanges.isEmpty()) {
      startRange = startRange.intersection(subtasksSpan);
      endRange = endRange.intersection(subtasksSpan);
    }
    debug(".. finally, start range={}", startRange);
    if (startRange.hasLowerBound()) {
      modifyTaskStart(node.getTask(), startRange.lowerEndpoint());
    }
    if (endRange.hasUpperBound()) {
      GPCalendarCalc cal = node.getTask().getManager().getCalendar();
      Date endDate = endRange.upperEndpoint();
      TimeUnit timeUnit = node.getTask().getDuration().getTimeUnit();
      if (DayMask.WORKING == (cal.getDayMask(endDate) & DayMask.WORKING)) {
        // in case if calculated end date falls on first day after holidays (say, on Monday)
        // we'll want to modify it a little bit, so that it falls on that holidays start
        // If we don't do this, it will be done automatically the next time task activities are recalculated,
        // and thus task end date will keep changing
        Date closestWorkingEndDate = cal.findClosest(
            endDate, timeUnit, GPCalendarCalc.MoveDirection.BACKWARD, GPCalendar.DayType.WORKING);
        Date closestNonWorkingEndDate = cal.findClosest(
            endDate, timeUnit, GPCalendarCalc.MoveDirection.BACKWARD, GPCalendar.DayType.NON_WORKING, closestWorkingEndDate);
        // If there is a non-working date between current task end and closest working date
        // then we're really just after holidays
        if (closestNonWorkingEndDate != null && closestWorkingEndDate.before(closestNonWorkingEndDate)) {
          // we need to adjust-right closest working date to position to the very beginning of the holidays interval
          Date nonWorkingPeriodStart = timeUnit.adjustRight(closestWorkingEndDate);
          if (nonWorkingPeriodStart.after(node.getTask().getStart().getTime())) {
            endDate = nonWorkingPeriodStart;
          }
        }
      }
      modifyTaskEnd(node.getTask(), endDate);
    }
  }

  private void modifyTaskEnd(Task task, Date newEnd) {
    if (task.getEnd().getTime().equals(newEnd)) {
      return;
    }
    GanttCalendar newEndCalendar = CalendarFactory.createGanttCalendar(newEnd);
    if (getDiagnostic() != null) {
      getDiagnostic().addModifiedTask(task, null, newEnd);
    }
    TaskMutator mutator = task.createMutator();
    mutator.setEnd(newEndCalendar);
    mutator.commit();
    myTaskChanged = true;
  }

  private void modifyTaskStart(Task task, Date newStart) {
    // [fork change] A2: derive the duration BEFORE the equality check below.
    //
    // It has to sit before it, not after: the early return exists so that a task whose start does
    // not move is not reported as modified, and a task can very well keep its start while its
    // duration changes -- a day off falling inside it, for instance. After the return the
    // derivation would never see that case.
    //
    // The early return itself is left exactly as it was. It carries the diagnostic when a project
    // is opened; without it every task in the project would stand in the "these have moved" list.
    myDurationDerivation.accept(task, newStart);
    if (task.getStart().getTime().equals(newStart)) {
      return;
    }
    GanttCalendar newStartCalendar = CalendarFactory.createGanttCalendar(newStart);
    if (getDiagnostic() != null) {
      getDiagnostic().addModifiedTask(task, newStart, null);
    }

    if (myTaskHierarchy.get().hasNestedTasks(task)) {
      TaskMutator mutator = task.createMutator();
      mutator.setStart(newStartCalendar);
      mutator.commit();
    } else {
      var mutator = task.createShiftMutator();
      TimeDuration shift = task.getManager().createLength(task.getDuration().getTimeUnit(), task.getStart().getTime(), newStart);
      mutator.shift(shift);
      mutator.commit();
    }
    myTaskChanged = true;
  }

  private void debug(String message, Object... params) {
    GPLogger.create("SchedulerImpl").debug(message, params, Collections.emptyMap());
  }

  private void error(Exception ex) {
    GPLogger.create("SchedulerImpl").error(ex.getMessage(), new Object[0], Collections.emptyMap(), ex);
  }
}
