/*
Copyright 2003-2012 Dmitry Barashev, GanttProject Team

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
package net.sourceforge.ganttproject.task;

import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.roles.Role;

/**
 * Created by IntelliJ IDEA.
 * 
 * @author bard Date: 05.02.2004
 */
public interface ResourceAssignment {
  Task getTask();

  HumanResource getResource();

  float getLoad();

  void setLoad(float load);

  /** Deletes this assignment */
  void delete();

  void setCoordinator(boolean responsible);

  boolean isCoordinator();

  /**
   * [fork change] Axis A: does this person's ABSENCE block the task?
   *
   * `false` is what the program does today: a day off of an assigned person removes that person's
   * hours from the day, it does not stop the task (see DaysOffDuration.kt). The default therefore
   * has to be `false`, and it is the plain Java default of a boolean field -- no constructor has
   * to remember it.
   *
   * P1 only carries the value. Nothing reads it yet.
   */
  void setBlocking(boolean blocking);

  boolean isBlocking();

  /**
   * [fork change] Axis B: does this person contribute NO work that counts towards the effort?
   *
   * Deliberately negated. Today every assigned person contributes, and `false` -- the plain Java
   * default of a boolean field and the value Jackson leaves in place for an attribute that is not
   * in the file -- means exactly that. A positive `contributesEffort` would have to default to
   * `true`, and every one of the three ResourceAssignment implementations plus every copying path
   * would have to remember to set it; a single forgotten spot would silently turn a person into a
   * non-contributor.
   *
   * Independent of [isBlocking]: someone can block without contributing (a person who has to be
   * present) and contribute without blocking.
   *
   * P1 only carries the value. Nothing reads it yet.
   */
  void setNoEffort(boolean noEffort);

  boolean isNoEffort();

  Role getRoleForAssignment();

  void setRoleForAssignment(Role role);
}
