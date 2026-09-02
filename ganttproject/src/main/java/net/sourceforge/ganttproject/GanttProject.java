/*
Copyright 2002-2019 Alexandre Thomas, BarD Software s.r.o

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
package net.sourceforge.ganttproject;

import biz.ganttproject.LoggerApi;
import biz.ganttproject.app.*;
import biz.ganttproject.lib.fx.TreeTableCellsKt;
import biz.ganttproject.platform.UpdateOptions;
import biz.ganttproject.storage.cloud.GPCloudOptions;
// [fork change] New imports for the Toggl token store and the connection check.
import net.sourceforge.ganttproject.gui.NotificationChannel;
import net.sourceforge.ganttproject.timetracking.ConnectionCheckMessageSink;
// Kotlin puts file-level declarations into a class <FileName>Kt.
import net.sourceforge.ganttproject.timetracking.ImportPeriodDialogKt;
import net.sourceforge.ganttproject.timetracking.TaskChoiceDialogKt;
import net.sourceforge.ganttproject.timetracking.TogglConnectionAction;
import net.sourceforge.ganttproject.timetracking.TogglImportAction;
// [Fork-Aenderung] Kapazitaetsverteilung.
import net.sourceforge.ganttproject.fork.AskBeforeWriting;
import net.sourceforge.ganttproject.fork.ForkI18nKt;
import net.sourceforge.ganttproject.fork.BackfillAction;
import net.sourceforge.ganttproject.fork.LevellingAction;
import net.sourceforge.ganttproject.fork.AutoBaselinesKt;
import net.sourceforge.ganttproject.fork.BaselineCatchUp;
import net.sourceforge.ganttproject.fork.BaselineCoverageKt;
import net.sourceforge.ganttproject.fork.BaselineGap;
import net.sourceforge.ganttproject.fork.LevellingStaleness;
import net.sourceforge.ganttproject.fork.LevellingRunNotifierKt;
import net.sourceforge.ganttproject.fork.EstimateQualityAction;
import net.sourceforge.ganttproject.fork.RecurrenceAction;
import net.sourceforge.ganttproject.timetracking.TogglTokenOptions;
import biz.ganttproject.storage.cloud.GPCloudStatusBar;
import com.beust.jcommander.Parameter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import javafx.stage.Stage;
import kotlin.Unit;
import net.sourceforge.ganttproject.action.*;
import net.sourceforge.ganttproject.action.edit.EditMenu;
import net.sourceforge.ganttproject.action.help.HelpMenu;
import net.sourceforge.ganttproject.action.project.ProjectMenu;
import net.sourceforge.ganttproject.action.resource.ResourceActionSet;
import net.sourceforge.ganttproject.action.view.ViewMenu;
import net.sourceforge.ganttproject.action.zoom.ZoomActionSet;
import net.sourceforge.ganttproject.chart.GanttChart;
import net.sourceforge.ganttproject.chart.TimelineChart;
import net.sourceforge.ganttproject.document.Document;
import net.sourceforge.ganttproject.document.Document.DocumentException;
import net.sourceforge.ganttproject.gui.UIConfiguration;
import net.sourceforge.ganttproject.gui.UIUtil;
import net.sourceforge.ganttproject.gui.scrolling.ScrollingManager;
import net.sourceforge.ganttproject.gui.view.ViewProvider;
import net.sourceforge.ganttproject.io.GPSaver;
import net.sourceforge.ganttproject.io.GanttXMLOpen;
import net.sourceforge.ganttproject.io.GanttXMLSaver;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.language.GanttLanguage.Event;
import net.sourceforge.ganttproject.parser.GPParser;
import net.sourceforge.ganttproject.parser.ParserFactory;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.resource.ResourceEvent;
import net.sourceforge.ganttproject.resource.ResourceView;
import net.sourceforge.ganttproject.roles.RoleManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/**
 * Main frame of the project
 */
public class GanttProject extends GanttProjectBase implements ResourceView, GanttLanguage.Listener {

  private final LoggerApi<Logger> boundsLogger = GPLogger.create("Window.Bounds");
  private final LoggerApi<Logger> gpLogger = GPLogger.create("GanttProject");

  // Chart component of the Gantt chart view.
  private final GanttGraphicArea area;

  // Chart component of the resource load view.
  private final ResourceLoadGraphicArea resourceChart;

  private final EditMenu myEditMenu;

  private final ProjectMenu myProjectMenu;

  /**
   * Informations for the current project.
   */
  public PrjInfos prjInfos = new PrjInfos();

  /**
   * Boolean to know if the file has been modify
   */
  public boolean askForSave = false;

  private final ZoomActionSet myZoomActions;

  private UIConfiguration myUIConfiguration;

  private final GanttOptions options;

  private ArrayList<GanttPreviousState> myPreviousStates = new ArrayList<>();

  /**
   * [fork change] The levelling menu item, kept so that the status-bar message can run the very
   * same one. Filled in {@link #getMenuBar()}; null until then.
   */
  private LevellingAction myLevellingAction;

  /**
   * [fork change] "Something has changed since the levelling last ran."
   *
   * A plain boolean fed by three listeners -- see {@link LevellingStaleness} for why it observes
   * events rather than computing, and for the feedback loop it has to survive. The three listeners
   * are registered in the constructor, where the task manager, the resource manager and the
   * calendar exist.
   *
   * ONE PER WINDOW, NOT ONE PER PROCESS. It is constructed here rather than taken from a shared
   * top-level value: a second window would otherwise share the mark and pile its listeners on top
   * of the first window's. The RUN NOTIFIER on the other hand IS the shared one -- that is the
   * instance `applyLevellingAsSingleEdit` reports through by default, so a mark hung on any other
   * instance would never be cleared.
   */
  private final LevellingStaleness myLevellingStaleness =
      new LevellingStaleness(LevellingRunNotifierKt.getLevellingRunNotifier());

  public LevellingStaleness getLevellingStaleness() {
    return myLevellingStaleness;
  }

  /**
   * [fork change] Runs the levelling menu item, for the button in the status-bar message.
   *
   * ON THE SWING THREAD, and that is the point of the method: the caller is JavaFX, the action
   * opens Swing dialogs. Doing the hop here keeps the JavaFX side from having to know that.
   */
  public void runLevellingFromMessage() {
    SwingUtilities.invokeLater(() -> {
      if (myLevellingAction != null) {
        myLevellingAction.actionPerformed(null);
      }
    });
  }

  /**
   * [fork change] The supplement behind the second statement of the status-bar message. Built in
   * {@link #getMenuBar()} because that is where the question dialog and the message sink live;
   * null until then.
   */
  private BaselineCatchUp myBaselineCatchUp;

  /**
   * [fork change] "Are there tasks in NO baseline at all?" -- the answer the status-bar message
   * needs.
   *
   * NOT CHEAP, and the caller knows it: {@link net.sourceforge.ganttproject.GanttPreviousState#load()}
   * re-parses every baseline's temporary file, measured at 2.3 ms for one baseline and 29.0 ms for
   * fifteen on a plan of 276 tasks. The display therefore asks only when it switches itself on --
   * see {@code LevellingStalenessBar}.
   */
  public BaselineGap getBaselineGap() {
    return BaselineCoverageKt.baselineGap(getTaskManager(), getBaselines());
  }

  /**
   * [fork change] Runs the supplement for the button in the status-bar message and reports back
   * when it is over.
   *
   * ON THE SWING THREAD, for the same reason as {@link #runLevellingFromMessage()}: the caller is
   * JavaFX and the action opens Swing dialogs. {@code onDone} arrives in EVERY case -- yes, no and
   * nothing-to-do -- because the display recomputes on it.
   */
  public void runBaselineCatchUpFromMessage(Runnable onDone) {
    SwingUtilities.invokeLater(() -> {
      if (myBaselineCatchUp == null) {
        onDone.run();
        return;
      }
      myBaselineCatchUp.run(() -> {
        onDone.run();
        return Unit.INSTANCE;
      });
    });
  }

  private final GanttChartTabContentPanel myGanttChartTabContent;

  private final ResourceChartTabContentPanel myResourceChartTabContent;

  private ParserFactory myParserFactory;

  private static Consumer<Boolean> ourQuitCallback = withSystemExit -> {
    if (withSystemExit) {
      System.exit(0);
    } else {
      System.err.println("Quit application was called without System.exit() request");
    }
  };

  private FXSearchUi mySearchUi;

  private final Supplier<GPAction> taskNewAction = Suppliers.memoize(myTaskActions::getCreateAction);
  private final Supplier<GPAction> resourceNewAction = Suppliers.memoize(myResourceActions::getResourceNewAction);
  private final Supplier<ArtefactAction> insertAction = Suppliers.memoize(() ->
    new ArtefactNewAction(
      () -> getViewManager().getActiveView().getCreateAction(),
      new Action[]{taskNewAction.get().asToolbarAction(), resourceNewAction.get().asToolbarAction()}
    )
  );


  public JMenuBar getMenuBar() {
    var bar = new JMenuBar();

    bar.add(myProjectMenu);
    bar.add(myEditMenu);

    ViewMenu viewMenu = new ViewMenu(getProject(), getViewManager(), getUiFacadeImpl().getDpiOption(), getUiFacadeImpl().getChartFontOption(), "view");
    bar.add(viewMenu);

    JMenu mTask = UIUtil.createTooltiplessJMenu(GPAction.createVoidAction("task"));
    mTask.add(myTaskActions.getCreateAction());
    mTask.add(myTaskActions.getPropertiesAction());
    mTask.add(myTaskActions.getDeleteAction());
    bar.add(mTask);

    JMenu mHuman = UIUtil.createTooltiplessJMenu(GPAction.createVoidAction("human"));
    ResourceActionSet resourceActionSet = myResourceActions;
    for (AbstractAction a : resourceActionSet.getActions()) {
      mHuman.add(a);
    }
    mHuman.add(resourceActionSet.getResourceSendMailAction());
    mHuman.add(resourceActionSet.getCloudResourceList());
    // [fork change] Connection check against Toggl. Sits in the resources menu, because the
    // token hangs off the resource. The check only reads and writes nothing.
    // The message is built HERE rather than through showNotificationDialog. That one embeds the
    // text in the templates <channel>.channel.itemTitle/itemBody -- and for the RSS channel those
    // templates do not exist. The box would then have displayed "rss.channel.itemBody" and
    // swallowed our message silently, because MessageFormat discards the argument without {0}.
    //
    // [fork change] THE HEADING COMES FROM THE CALLER, no longer fixed to Toggl.
    //
    // All actions of this fork used to share one box -- and with it the title "Toggl connection".
    // Seen on screen: the message "No recurrence is entered" appeared under that heading. Whoever
    // reads it looks for the fault in a connection that has nothing to do with the matter.
    //
    // A Supplier and not a string, for the same reason that notificationTitle is a getter: the
    // language can change while the program runs, and a text captured here would stay in the
    // language the program started in.
    java.util.function.Function<java.util.function.Supplier<String>, ConnectionCheckMessageSink> messageSink =
        title -> (isProblem, message) -> {
          var manager = getUIFacade().getNotificationManager();
          manager.addNotifications(List.of(manager.createNotification(
              isProblem ? NotificationChannel.WARNING : NotificationChannel.RSS,
              title.get(),
              "<p>" + message.replace("\n", "<br>") + "</p>",
              null)));
        };
    ConnectionCheckMessageSink togglMessages =
        messageSink.apply(TogglConnectionAction::getNotificationTitle);
    // Deriving effort and levelling capacity belong together and share their heading.
    ConnectionCheckMessageSink capacityMessages =
        messageSink.apply(() -> ForkI18nKt.forkText("fork.capacity.title"));
    ConnectionCheckMessageSink recurrenceMessages =
        messageSink.apply(() -> ForkI18nKt.forkText("fork.recurrence.title"));
    mHuman.add(new TogglConnectionAction(getHumanResourceManager(), togglMessages));

    // [fork change] Import of the Toggl times. A question is asked before writing: the preview
    // names the totals AND what gets skipped. showOptionDialog does not block, so the action gets
    // a callback instead of a return value.
    mHuman.add(new TogglImportAction(
        getTaskManager(),
        getHumanResourceManager(),
        getProject().getTaskCustomColumnManager(),
        getProjectDatabase(),
        getUndoManager(),
        togglMessages,
        (message, answer) -> getUIFacade().showOptionDialog(
            JOptionPane.QUESTION_MESSAGE,
            message,
            new Action[] {
                OkAction.create("ok", () -> { answer.accept(true); return Unit.INSTANCE; }),
                CancelAction.create("cancel", () -> { answer.accept(false); return Unit.INSTANCE; })
            }),
        ImportPeriodDialogKt.getASK_FOR_THE_PERIOD(),
        TaskChoiceDialogKt.getASK_FOR_THE_TASKS()));

    // [fork change] Capacity levelling. Sits in the resources menu, because both hang off the
    // resources: effort per day and who is available when.
    //
    // BOTH ASK BEFORE WRITING, and both are ONE undo step. In a plan that has grown, levelling
    // can move two thirds of all Tasks -- doing that unasked would be exactly the silent change
    // this fork has found several times.
    AskBeforeWriting askBeforeWriting = (message, answer) -> getUIFacade().showOptionDialog(
        JOptionPane.QUESTION_MESSAGE,
        message,
        new Action[] {
            OkAction.create("ok", () -> { answer.invoke(true); return Unit.INSTANCE; }),
            CancelAction.create("cancel", () -> { answer.invoke(false); return Unit.INSTANCE; })
        });
    mHuman.add(new BackfillAction(
        getTaskManager(),
        getHumanResourceManager(),
        getProject().getTaskCustomColumnManager(),
        getHumanResourceManager().getCustomPropertyManager(),
        getProjectDatabase(),
        getUndoManager(),
        (isProblem, message) -> { capacityMessages.show(isProblem, message); return Unit.INSTANCE; },
        askBeforeWriting));
    // [fork change] KEPT IN A FIELD, and not only hung in the menu. The message in the status bar
    // carries a button that has to run THE SAME levelling -- one path, one set of questions before
    // writing, one undo step. Copying the action's body into the button would be a second levelling
    // that drifts from the first. getMenuBar() is called once at startup, but nothing says so, so
    // the field is filled here rather than depending on that.
    myLevellingAction = new LevellingAction(
        getTaskManager(),
        getHumanResourceManager(),
        getProject().getTaskCustomColumnManager(),
        getHumanResourceManager().getCustomPropertyManager(),
        getUndoManager(),
        // Fetch the baselines FRESH EVERY TIME: the field myPreviousStates is replaced by a new
        // list when a project is closed (line 819). A list captured here would be orphaned after
        // the first file is opened -- measured on the machine.
        () -> (java.util.List<net.sourceforge.ganttproject.GanttPreviousState>) getBaselines(),
        java.time.LocalDate::now,
        (isProblem, message) -> { capacityMessages.show(isProblem, message); return Unit.INSTANCE; },
        askBeforeWriting);
    mHuman.add(myLevellingAction);

    // [fork change] The supplement behind the second statement of the status-bar message. It has
    // NO menu item of its own on purpose: it is only ever reached from that message, and the
    // message only appears when there is something to supplement. Built here because this is where
    // the question dialog and the capacity message sink are.
    //
    // The baselines are fetched FRESH EVERY TIME for the reason the levelling action documents
    // above: the field behind getBaselines() is replaced when a project is closed.
    myBaselineCatchUp = new BaselineCatchUp(
        getTaskManager(),
        () -> (java.util.List<net.sourceforge.ganttproject.GanttPreviousState>) getBaselines(),
        java.time.LocalDateTime::now,
        (isProblem, message) -> { capacityMessages.show(isProblem, message); return Unit.INSTANCE; },
        askBeforeWriting,
        AutoBaselinesKt.MAX_AUTO_BASELINES_PER_KIND);

    // [fork change] The estimating-quality evaluation. Writes NOTHING and therefore does not ask
    // either.
    //
    // A WINDOW, NOT A NOTIFICATION, and that is measured on screen: through togglMessages the
    // message ends up as a small mark at the bottom right that has to be clicked first. For a
    // success message that is enough; a multi-line report that is meant to be READ is practically
    // invisible there -- on the first run it was not found at all and the menu item seemed to do
    // nothing.
    mHuman.add(new EstimateQualityAction(
        getTaskManager(),
        getProject().getTaskCustomColumnManager(),
        (isProblem, message) -> {
          getUIFacade().showOptionDialog(
              isProblem ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE,
              message,
              new Action[] { OkAction.create("ok", () -> Unit.INSTANCE) });
          return Unit.INSTANCE;
        }));

    // [fork change] Recurring Tasks: one Task with a recurrence becomes many. Asks beforehand,
    // creates nothing twice and is ONE undo step.
    mHuman.add(new RecurrenceAction(
        getTaskManager(),
        getProject().getTaskCustomColumnManager(),
        getProjectDatabase(),
        getUndoManager(),
        (isProblem, message) -> { recurrenceMessages.show(isProblem, message); return Unit.INSTANCE; },
        askBeforeWriting));

    HelpMenu helpMenu = new HelpMenu(getProject(), getUIFacade(), getProjectUIFacade());
    bar.add(mHuman);
    bar.add(helpMenu.createMenu());
    return bar;
  }

  public GanttProject(Stage stage) {
    super(stage);
    LoggerApi<Logger> startupLogger = GPLogger.create("Window.Startup");
    startupLogger.debug("Creating main frame...");
    ToolTipManager.sharedInstance().setInitialDelay(200);
    ToolTipManager.sharedInstance().setDismissDelay(60000);

    getProjectImpl().getHumanResourceManager().addView(this);
    myCalendar.addListener(GanttProject.this::setModified);

    // [fork change] The three listeners of the "levelling is out of date" mark, next to the two
    // that already stand here and for the same reason: this is where the managers exist.
    //
    // The mark WRITES NOTHING; it only reads its own events. It is deliberately registered
    // alongside the existing views rather than inside LevellingStaleness, so that everything the
    // program listens to stays visible in one place.
    getTaskManager().addTaskListener(myLevellingStaleness.getTaskListener());
    getProjectImpl().getHumanResourceManager().addView(myLevellingStaleness.getResourceView());
    myCalendar.addListener(myLevellingStaleness.getCalendarListener());

    startupLogger.debug("1. loading look'n'feels");
    options = new GanttOptions(getRoleManager(), getDocumentManager(), false);
    myUIConfiguration = options.getUIConfiguration();
    myUIConfiguration.setChartFontOption(getUiFacadeImpl().getChartFontOption());
    myUIConfiguration.setDpiOption(getUiFacadeImpl().getDpiOption());

    addProjectEventListener(getTaskManager().getProjectListener());
    addProjectEventListener(getHumanResourceManager().getProjectListener());
    getActiveCalendar().addListener(getTaskManager().getCalendarListener());

    area = new GanttGraphicArea(this, getTaskManager(), getZoomManager(), getUndoManager(),
        myTaskTableChartConnector,
      Suppliers.memoize(() -> myTaskTableSupplier.get().getActionConnector())::get);
    resourceChart = new ResourceLoadGraphicArea(this, getZoomManager(), myResourceTableChartConnector);

    options.addOptionGroups(getUIFacade().getOptions());
    options.addOptionGroups(getUIFacade().getGanttChart().getOptionGroups());
    options.addOptionGroups(getUIFacade().getResourceChart().getOptionGroups());
    options.addOptionGroups(getProjectUIFacade().getOptionGroups());
    options.addOptionGroups(getDocumentManager().getNetworkOptionGroups());
    options.addOptions(GPCloudOptions.INSTANCE.getOptionGroup());
    options.addOptions(getRssFeedChecker().getOptions());
    options.addOptions(UpdateOptions.INSTANCE.getOptionGroup());
    // [fork change] Toggl token per person. Belongs in the application settings
    // (~/.ganttproject) and explicitly NOT in the project file - that one is shared.
    options.addOptions(TogglTokenOptions.INSTANCE.getOptionGroup());
    options.addOptions(myTaskManagerConfig.getTaskOptions());
    startupLogger.debug("2. loading options");
    initOptions();

    getUIFacade().setLookAndFeel(getUIFacade().getLookAndFeel());
    getUiFacadeImpl().getAppFontOption().addChangeValueListener(event -> getGanttChart().reset());
    TreeTableCellsKt.initFontProperty(getUiFacadeImpl().getAppFontOption(), getUiFacadeImpl().getRowPaddingOption());
    TreeTableCellsKt.initColorProperties();
    getZoomManager().addZoomListener(area.getZoomListener());

    ScrollingManager scrollingManager = getScrollingManager();
    scrollingManager.addScrollingListener(area.getViewState());
    scrollingManager.addScrollingListener(resourceChart.getViewState());

    startupLogger.debug("3. creating menus...");
    myZoomActions = new ZoomActionSet(getZoomManager());
    myProjectMenu = new ProjectMenu(this, stage, "project");
    myEditMenu = new EditMenu(getProject(), getUIFacade(), getViewManager(), () -> mySearchUi.requestFocus(), "edit");


    startupLogger.debug("4. creating views...");

    myGanttChartTabContent = new GanttChartTabContentPanel(
        getProject(), getUIFacade(), area.getJComponent(), area.getCursorProperty(), area::buildContextMenu,
        getUIConfiguration(), myTaskTableSupplier, myTaskActions, myUiInitializationPromise);

    myResourceChartTabContent = new ResourceChartTabContentPanel(getProject(), getUIFacade(),
      myResourceTableSupplier, resourceChart, resourceChart.getCursorProperty(), resourceChart::buildContextMenu);
    myUiInitializationPromise.activate(getUiFacadeImpl());
//++
//    addComponentListener(new ComponentAdapter() {
//      @Override
//      public void componentShown(ComponentEvent e) {
//        SwingUtilities.invokeLater(() -> {
//          getGanttChart().reset();
//          getResourceChart().reset();
//          // This will clear any modifications which might be caused by
//          // adjusting widths of table columns during initial layout process.
//          getProject().setModified(false);
//        });
//      }
//    });
    startupLogger.debug("5. calculating size and packing...");

    startupLogger.debug("6. changing language ...");
    languageChanged(null);
    // Add Listener after language update (to be sure that it is not updated
    // twice)
    language.addListener(this);

    startupLogger.debug("7. first attempt to restore bounds");
  //++
    //    addWindowListener(new WindowAdapter() {
//      @Override
//      public void windowClosing(WindowEvent windowEvent) {
//        quitApplication(true);
//      }
//
//      @Override
//      public void windowOpened(WindowEvent e) {
//        boundsLogger.debug("Resizing window...");
//        boundsLogger.debug("Bounds after opening: {}", new Object[]{GanttProject.this.getBounds()}, ImmutableMap.of());
//        restoreBounds();
//        // It is important to run aligners after look and feel is set and font sizes
//        // in the UI manager updated.
//        SwingUtilities.invokeLater(() -> {
//          for (RowHeightAligner aligner : myRowHeightAligners) {
//            aligner.optionsChanged();
//          }
//        });
//        getUiFacadeImpl().getDpiOption()
//            .addChangeValueListener(event -> SwingUtilities.invokeLater(() -> getContentPane().doLayout()));
//        getGanttChart().reset();
//        getResourceChart().reset();
//        // This will clear any modifications which might be caused by
//        // adjusting widths of table columns during initial layout process.
//        getProject().setModified(false);
//      }
//    });

    startupLogger.debug("8. finalizing...");
    // applyComponentOrientation(GanttLanguage.getInstance()
    // .getComponentOrientation());
    // TODO: this shall be registered just once
    getProjectUIFacade().getProjectOpenActivityFactory().addBuilder(
      GanttProjectImplKt.createProjectModificationListener(this, getUIFacade())
    );
    //++addMouseListenerToAllContainer(this.getComponents());

    // Add globally available actions/key strokes
//    GPAction viewCycleForwardAction = new ViewCycleAction(getViewManager(), true);
//    UIUtil.pushAction(getTabs(), true, viewCycleForwardAction.getKeyStroke(), viewCycleForwardAction);
//
//    GPAction viewCycleBackwardAction = new ViewCycleAction(getViewManager(), false);
//    UIUtil.pushAction(getTabs(), true, viewCycleBackwardAction.getKeyStroke(), viewCycleBackwardAction);

    try {
      myObservableDocument.set(getDocumentManager().newUntitledDocument());
    } catch (IOException e) {
      gpLogger.error(Arrays.toString(e.getStackTrace()), new Object[]{}, ImmutableMap.of(), e);
    }
    DesktopIntegration.setup(GanttProject.this);
  }

  public WindowGeometry getWindowGeometry() {
    return new WindowGeometry(options.getX(), options.getY(), options.getWidth(), options.getHeight(), options.isMaximized());
  }

  public void setWindowGeometry(WindowGeometry value) {
    options.setWindowPosition((int)value.getLeftX(), (int)value.getTopY());
    options.setWindowSize((int)value.getWidth(), (int)value.getHeight(), value.isMaximized());
  }

  public List<GPAction> getAppLevelActions() {
    return List.of(insertAction.get(), getViewManager().getDeleteAction(), getViewManager().getPropertiesAction());
  }
  private void restoreBounds() {
    //++
    //    if (options.isLoaded()) {
//      if (options.isMaximized()) {
//        setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
//      }
//      Rectangle bounds = new Rectangle(options.getX(), options.getY(), options.getWidth(), options.getHeight());
//      boundsLogger.debug("Bounds stored in the  options: {}", new Object[]{bounds}, ImmutableMap.of());
//
//      UIUtil.MultiscreenFitResult fit = UIUtil.multiscreenFit(bounds);
//      // If more than 1/4 of the rectangle is visible on screen devices then leave it where it is
//      if (fit.totalVisibleArea < 0.25 || Math.max(bounds.width, bounds.height) < 100) {
//        // Otherwise if it is visible on at least one device, try to fit it there
//        if (fit.argmaxVisibleArea != null) {
//          bounds = fitBounds(fit.argmaxVisibleArea, bounds);
//        } else {
//          UIUtil.MultiscreenFitResult currentFit = UIUtil.multiscreenFit(this.getBounds());
//          if (currentFit.argmaxVisibleArea != null) {
//            // If there are no devices where rectangle is visible, fit it on the current device
//            bounds = fitBounds(currentFit.argmaxVisibleArea, bounds);
//          } else {
//            boundsLogger.debug(
//                "We have not found the display corresponding to bounds {}. Leaving the window where it is",
//                new Object[]{bounds}, ImmutableMap.of()
//            );
//            return;
//          }
//        }
//      }
//++      setBounds(bounds);
//    }
  }

//  static private Rectangle fitBounds(GraphicsConfiguration display, Rectangle bounds) {
//    Rectangle displayBounds = display.getBounds();
//    Rectangle visibleBounds = bounds.intersection(displayBounds);
//    int fitX = visibleBounds.x;
//    if (fitX + bounds.width > displayBounds.x + displayBounds.width) {
//      fitX = Math.max(displayBounds.x, displayBounds.x + displayBounds.width - bounds.width);
//    }
//    int fitY = visibleBounds.y;
//    if (fitY + bounds.height > displayBounds.y + displayBounds.height) {
//      fitY = Math.max(displayBounds.y, displayBounds.y + displayBounds.height - bounds.height);
//    }
//    return new Rectangle(fitX, fitY, bounds.width, bounds.height);
//
//  }


  private void initOptions() {
    options.setUIConfiguration(myUIConfiguration);
    options.load();
    myUIConfiguration = options.getUIConfiguration();
  }

//  private void addMouseListenerToAllContainer(Component[] containers) {
//    for (Component container : containers) {
//      container.addMouseListener(getStopEditingMouseListener());
//      if (container instanceof Container) {
//        addMouseListenerToAllContainer(((Container) container).getComponents());
//      }
//    }
//  }

//  /**
//   * @return A mouseListener that stop the edition in the ganttTreeTable.
//   */
//  private MouseListener getStopEditingMouseListener() {
//    if (myStopEditingMouseListener == null)
//      myStopEditingMouseListener = new MouseAdapter() {
//        // @Override
//        // public void mouseClicked(MouseEvent e) {
//        // if (e.getSource() != bNew && e.getClickCount() == 1) {
//        // tree.stopEditing();
//        // }
//        // if (e.getButton() == MouseEvent.BUTTON1
//        // && !(e.getSource() instanceof JTable)
//        // && !(e.getSource() instanceof AbstractButton)) {
//        // Task taskUnderPointer =
//        // area.getChartImplementation().findTaskUnderPointer(e.getX(),
//        // e.getY());
//        // if (taskUnderPointer == null) {
//        // getTaskSelectionManager().clear();
//        // }
//        // }
//        // }
//      };
//    return myStopEditingMouseListener;
//  }

  /**
   * @return the options of ganttproject.
   */
  public GanttOptions getGanttOptions() {
    return options;
  }

  /**
   * Function to change language of the project
   */
  @Override
  public void languageChanged(Event event) {
//++    applyComponentOrientation(language.getComponentOrientation());
    area.repaint();
    resourceChart.repaint();

//++    applyComponentOrientation(language.getComponentOrientation());
  }

  public GPCloudStatusBar createStatusBar() {
    var result  = new GPCloudStatusBar(
      myObservableDocument, getUIFacade(), getProjectUIFacade(), getProject()
    );
    result.getLockPanel().getStylesheets().add("biz/ganttproject/app/StatusBar.css");
    return result;
  }
  /**
   * Create the button on toolbar
   */
  public FXToolbarBuilder createToolbar() {
    FXToolbarBuilder builder = new FXToolbarBuilder();
    builder.addButton(myProjectMenu.getOpenProjectAction().asToolbarAction())
        .addButton(myProjectMenu.getSaveProjectAction().asToolbarAction())
        .addWhitespace();

    builder.addButton(taskNewAction.get().asToolbarAction()).addButton(resourceNewAction.get().asToolbarAction());
    builder.addButton(getViewManager().getDeleteAction().asToolbarAction());

    var propertiesAction = getViewManager().getPropertiesAction();

    //++UIUtil.registerActions(getRootPane(), false, newAction, propertiesAction, deleteAction);
    // TODO: it might be necessary to uncomment it
    //UIUtil.registerActions(myGanttChartTabContent.getComponent(), true, newAction, propertiesAction, deleteAction);
//    UIUtil.registerActions(myResourceChartTabContent.getComponent(), true, insertAction.get(), propertiesAction,
//      getViewManager().getDeleteAction());
//    getTabs().getModel().addChangeListener(e -> {
//      // Tell artefact actions that the active provider changed, so they
//      // are able to update their state according to the current delegate
//      newAction.actionStateChanged();
//      propertiesAction.actionStateChanged();
//      deleteAction.actionStateChanged();
//      getTabs().getSelectedComponent().requestFocus();
//    });

    builder
        .addWhitespace()
        .addButton(propertiesAction)
        .addButton(getCutAction().asToolbarAction())
        .addButton(getCopyAction().asToolbarAction())
        .addButton(getPasteAction().asToolbarAction())
        .addWhitespace()
        .addButton(myEditMenu.getUndoAction().asToolbarAction())
        .addButton(myEditMenu.getRedoAction().asToolbarAction());
    mySearchUi = new FXSearchUi(getProject(), getUIFacade(), myEditMenu.getSearchAction());
    builder.addSearchBox(mySearchUi);
    builder.withClasses("toolbar-common", "toolbar-main", "toolbar-big");
    //return result;
    getWindowOpenedBarrier().await(opened -> {
      if (opened) {
        insertAction.get().init();
//        deleteAction.get().init();
//        propertiesAction.init();
      }
      return Unit.INSTANCE;
    });
    return builder;
  }

  void doShow() {
    getRssFeedChecker().run();
  }

  @Override
  public @NotNull List<GanttPreviousState> getBaselines() {
    return myPreviousStates;
  }

  /**
   * Create a new project
   */
  public void newProject() {
    getProjectUIFacade().createProject(getProject());
  }

  @Override
  public void open(Document document) throws IOException, DocumentException {
    document.read();
    // [fork change] AFTER reading: create the daily-rate columns in case the file does not bring
    // them along. Called before reading, loading would fail for every file that contains the same
    // column ("Column with ID=hours_per_day is already registered") -- measured on the machine,
    // see ensureCapacityColumns().
    getProjectImpl().ensureCapacityColumns();
    getDocumentManager().addToRecentDocuments(document);
    //myMRU.add(document.getPath(), true);
    myObservableDocument.set(document);
    updateTitle();
    refresh();
    getProjectImpl().fireProjectOpened();
  }

  /**
   * @return the UIConfiguration.
   */
  @Override
  public @NotNull UIConfiguration getUIConfiguration() {
    return myUIConfiguration;
  }

  private boolean myQuitEntered = false;

  /**
   * Quit the application
   */
  @Override
  public Barrier<Boolean> quitApplication(boolean withSystemExit) {
    if (myQuitEntered) {
      return new ResolvedBarrier<>(true);
    }
    myQuitEntered = true;
    try {
      options.setUIConfiguration(myUIConfiguration);
      options.save();
      var barrier = getProjectUIFacade().ensureProjectSaved(getProject());
      barrier.await(result -> {
        if (result) {
          // [fork change] If closing fails, the program quits anyway -- and the cause is in the
          // log.
          //
          // DEMONSTRATED: after "Beenden" and "Nicht speichern" the process was left behind
          // without a window. The last log line was options.save() from this method, nothing
          // after it. So getProject().close() threw an exception that the barrier swallowed;
          // doQuitApplication was never reached. The window disappeared regardless, because the
          // second callback on the same barrier (GanttProjectFxApp) calls Platform.exit().
          //
          // Whoever wants to quit and has chosen "do not save" should get to quit -- a process
          // that carries on invisibly is worse than a project that was not closed cleanly.
          try {
            getProject().close();
          } catch (Throwable e) {
            GPLogger.log(e);
          }
          doQuitApplication(withSystemExit);
        } else {
          //++setVisible(true);
        }
        return Unit.INSTANCE;
      });
      return barrier;
    } finally {
      myQuitEntered = false;
    }
  }

  public void setAskForSave(boolean afs) {
    getProjectImpl().fireProjectModified(afs, (ex) -> getUIFacade().showErrorDialog(ex) );
    askForSave = afs;
  }

  public GanttGraphicArea getArea() {
    return this.area;
  }

  public GPAction getCopyAction() {
    return getViewManager().getCopyAction();
  }

  public GPAction getCutAction() {
    return getViewManager().getCutAction();
  }

  public GPAction getPasteAction() {
    return getViewManager().getPasteAction();
  }

  @Override
  public ZoomActionSet getZoomActionSet() {
    return myZoomActions;
  }

  @Override
  public ViewProvider getGanttViewProvider() {
    return myGanttChartTabContent;
  }

  @Override
  public ViewProvider getResourceViewProvider() {
    return myResourceChartTabContent;
  }

  public static class Args {

    @Parameter(names = "-log", description = "Enable logging", arity = 1)
    public boolean log = true;

    @Parameter(names = "-log_file", description = "Log file name")
    public String logFile = "auto";

    @Parameter(names = {"-h", "-help"}, description = "Print usage")
    public boolean help = false;

    @Parameter(names = {"-version"}, description = "Print version number")
    public boolean version = false;

    @Parameter(names = "--fix-menu-bar-title", description = "Fixes the application title in the menu bar on Linux with Unity desktop environment")
    public boolean fixMenuBarTitle = false;

    @Parameter(description = "Input file name")
    public List<String> file = null;
  }

  // ///////////////////////////////////////////////////////
  // IGanttProject implementation
  @Override
  public @NotNull String getProjectName() {
    return prjInfos.getName();
  }

  @Override
  public void setProjectName(@NotNull String projectName) {
    prjInfos.setName(projectName);
    setAskForSave(true);
  }

  @Override
  public @NotNull String getDescription() {
    return Objects.requireNonNullElse(prjInfos.getDescription(), "");
  }

  @Override
  public void setDescription(@NotNull String description) {
    prjInfos.setDescription(description);
    setAskForSave(true);
  }

  @Override
  public @NotNull String getOrganization() {
    return prjInfos.getOrganization();
  }

  @Override
  public void setOrganization(@NotNull String organization) {
    prjInfos.setOrganization(organization);
    setAskForSave(true);
  }

  @Override
  public @NotNull String getWebLink() {
    return prjInfos.getWebLink();
  }

  @Override
  public void setWebLink(@NotNull String webLink) {
    prjInfos.setWebLink(webLink);
    setAskForSave(true);
  }

  @Override
  public @NotNull HumanResourceManager getHumanResourceManager() {
    return getProjectImpl().getHumanResourceManager();
  }

  @Override
  public @NotNull RoleManager getRoleManager() {
    return getProjectImpl().getRoleManager();
  }

  @Override
  public @NotNull Document getDocument() {
    return myObservableDocument.get();
  }

  @Override
  public void setDocument(@NotNull Document document) {
    myObservableDocument.set(document);
  }

  @Override
  public void setModified() {
    setModified(true);
  }

  @Override
  public void setModified(boolean modified) {
    setAskForSave(modified);
    updateTitle();
  }

  @Override
  public boolean isModified() {
    return askForSave;
  }

  @Override
  public void close() {
    getProjectImpl().fireProjectClosed();
    prjInfos = new PrjInfos();
    RoleManager.Access.getInstance().clear();
    getTaskCustomColumnManager().reset();
    getResourceCustomPropertyManager().reset();

    for (GanttPreviousState myPreviousState : myPreviousStates) {
      myPreviousState.remove();
    }
    myPreviousStates = new ArrayList<>();
    myCalendar.reset();
    //myFacadeInvalidator.projectClosed();
  }

  @Override
  protected ParserFactory getParserFactory() {
    if (myParserFactory == null) {
      myParserFactory = new ParserFactoryImpl();
    }
    return myParserFactory;
  }

  // ///////////////////////////////////////////////////////////////
  // ResourceView implementation
  @Override
  public void resourceAdded(ResourceEvent event) {
    SwingUtilities.invokeLater(this::setModified);
  }

  @Override
  public void resourcesRemoved(ResourceEvent event) {
    SwingUtilities.invokeLater(this::setModified);
  }

  @Override
  public void resourceChanged(ResourceEvent e) {
    SwingUtilities.invokeLater(this::setModified);
  }

  @Override
  public void resourceAssignmentsChanged(ResourceEvent e) {
    SwingUtilities.invokeLater(this::setModified);
  }

  @Override
  public void resourceStructureChanged() {
    SwingUtilities.invokeLater(this::setModified);
  }

  @Override
  public void resourceModelReset() {
  }

  // ///////////////////////////////////////////////////////////////
  // UIFacade

  @Override
  public GanttChart getGanttChart() {
    return getArea();
  }

  @Override
  public TimelineChart getResourceChart() {
    return resourceChart;
  }

  private class ParserFactoryImpl implements ParserFactory {
    @Override
    public GPParser newParser() {

      return new GanttXMLOpen(prjInfos, getTaskManager(), getUIFacade());

    }

    @Override
    public GPSaver newSaver() {
      return new GanttXMLSaver(GanttProject.this, getArea(), getUIFacade(),
        myGanttChartTabContent,
        myResourceChartTabContent,
        () -> myTaskTableSupplier.get().getColumnList(), GanttProject.this::getTaskFilterManager,
        () -> myResourceTableSupplier.get().getColumnList());
    }
  }

  public static void setApplicationQuitCallback(Consumer<Boolean> callback) {
    ourQuitCallback = callback;
  }

  public static void doQuitApplication(boolean withSystemExit) {
    ourQuitCallback.accept(withSystemExit);
  }
  @Override
  public void refresh() {
    getTaskFilterManager().refresh();
    getViewManager().refresh();
    //++super.repaint();
  }

}
