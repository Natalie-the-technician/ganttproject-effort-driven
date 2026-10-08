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
package net.sourceforge.ganttproject.importer;

import java.io.File;

import biz.ganttproject.app.WizardPage;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.prefs.Preferences;

import biz.ganttproject.core.option.GPOptionGroup;
import net.sourceforge.ganttproject.IGanttProject;
import net.sourceforge.ganttproject.gui.UIFacade;

public interface Importer {
  String getID();
  String getFileTypeDescription();

  String getFileNamePattern();

  GPOptionGroup[] getSecondaryOptions();

  String EXTENSION_POINT_ID = "net.sourceforge.ganttproject.importer";

  void setContext(IGanttProject project, UIFacade uiFacade, Preferences pluginPreferences);

  boolean isReady();

  /**
   * A question this importer needs answered before it may run, or null when there is none.
   *
   * ADDED IN THIS FORK, and deliberately NOT folded into {@link #isReady()}: that method reads a
   * field of {@code ImporterBase} which {@code setFile} no longer writes (the file lives in the
   * wizard model now), so it answers false for every importer here and nothing may be built on
   * it. The returned text is shown to the user, so it says what to decide and not that something
   * is missing.
   *
   * <p>THE WIZARD ASKS THIS ON EVERY REFRESH and keeps the finish button disabled while it
   * answers non-null, which is what makes an unanswered question stop the import instead of
   * letting it run with a silent default. An importer that returns non-null here must also refuse
   * in its own {@link #run()}: the button is the first lock, not the only one.
   */
  default String getUnansweredQuestion() {
    return null;
  }

  void run();

  void setFile(File file);

  WizardPage getCustomPage();

  void setModel(@NotNull ImporterWizardModel wizardModel);
}
