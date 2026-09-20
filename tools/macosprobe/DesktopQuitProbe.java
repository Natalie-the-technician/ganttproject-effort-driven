/*
Copyright 2026

NEW FILE IN THIS FORK -- not present in the original GanttProject, and not part of the program.
It is a measuring instrument for F27 and nothing else; nothing in the build refers to it.

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

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * THE QUESTION THIS ANSWERS, and it is the only open question in F27:
 *
 * `DesktopIntegration.maybeQuit` answers the operating system's quit request with
 * `quitResponse.performQuit()` and -- unlike the other two quit paths -- does NOT call
 * `Platform.exit()`. Whether that is a defect depends entirely on what AWT's `performQuit()` does
 * on macOS: if it ends the process by itself, nothing is wrong; if it only tells AppKit "yes, you
 * may terminate me" and AppKit then waits for a JavaFX platform that never exits, the process
 * stays. That could not be decided without a Mac, and so F27 says so instead of claiming a defect.
 *
 * THIS PROGRAM IS THE SHAPE OF `maybeQuit` WITH EVERYTHING ELSE REMOVED. It is deliberately not
 * GanttProject: GanttProject needs a project, a window and a user who answers "don't save", none of
 * which exist on a build machine. What is kept is exactly the part under suspicion -- a JavaFX
 * platform that is up and will not exit implicitly, an AWT quit handler that calls `performQuit()`
 * and nothing else, and a main thread that is parked the way `appBuilder.launch()` parks it.
 *
 * WHAT A RESULT HERE IS WORTH, said plainly because it is easy to overclaim: if the process dies,
 * that is evidence about `performQuit()` on this JDK and this macOS -- not proof that GanttProject
 * quits, because GanttProject has more threads and more windows. If it survives, that is the
 * stronger direction: the minimal case already fails, and GanttProject cannot do better with more
 * on top of it.
 *
 * MODES
 *   report              print what this machine can do and exit. Answers "does the quit handler
 *                       even install on a runner" without starting anything.
 *   handler             install the handler that reproduces F27 -- performQuit(), no Platform.exit()
 *   handler-fixed       the same, with the Platform.exit() F27 proposes. The positive control: if
 *                       this one does not die either, the experiment says nothing about F27 and the
 *                       run has to be thrown away rather than reported.
 *
 * In the two handler modes the program prints READY and then waits to be quit from outside.
 */
public class DesktopQuitProbe {

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "report";
    System.out.println("PROBE mode=" + mode);
    System.out.println("PROBE pid=" + ProcessHandle.current().pid());
    System.out.println("PROBE os.name=" + System.getProperty("os.name")
      + " os.version=" + System.getProperty("os.version")
      + " java.version=" + System.getProperty("java.version")
      + " java.vendor=" + System.getProperty("java.vendor"));
    System.out.println("PROBE java.awt.headless=" + System.getProperty("java.awt.headless"));

    // Asked before Desktop is touched: GraphicsEnvironment decides headlessness once and for all,
    // and Desktop.getDesktop() throws HeadlessException rather than answering.
    boolean headless = GraphicsEnvironment.isHeadless();
    System.out.println("PROBE GraphicsEnvironment.isHeadless=" + headless);
    System.out.println("PROBE Desktop.isDesktopSupported=" + Desktop.isDesktopSupported());

    boolean quitHandlerSupported = false;
    if (!headless && Desktop.isDesktopSupported()) {
      try {
        quitHandlerSupported = Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER);
      } catch (Throwable e) {
        System.out.println("PROBE isSupported threw: " + e);
      }
    }
    System.out.println("PROBE APP_QUIT_HANDLER supported=" + quitHandlerSupported);

    if ("report".equals(mode)) {
      // Installing it is itself a measurement: "supported" and "installs" are two different claims,
      // and DesktopAdapter.install swallows UnsupportedOperationException, so on a machine where it
      // silently does nothing GanttProject would look the same as one where it works.
      System.out.println("PROBE setQuitHandler=" + tryToInstall(() -> {}));
      System.out.flush();
      return;
    }

    final boolean callPlatformExit = "handler-fixed".equals(mode);
    final CountDownLatch quitRequested = new CountDownLatch(1);

    // The JavaFX platform, up and told not to exit by itself -- exactly the state GanttProject is
    // in while it runs. Without this the process would die for a reason that has nothing to do with
    // the quit handler, and the measurement would be worthless.
    try {
      javafx.application.Platform.startup(() -> System.out.println("PROBE javafx started"));
      javafx.application.Platform.setImplicitExit(false);
      System.out.println("PROBE javafx=up");
    } catch (Throwable e) {
      System.out.println("PROBE javafx FAILED: " + e);
      System.out.flush();
      System.exit(3);
    }

    String installed = tryToInstall(() -> {
      System.out.println("PROBE quit handler CALLED");
      System.out.flush();
      quitRequested.countDown();
      if (callPlatformExit) {
        System.out.println("PROBE calling Platform.exit()");
        System.out.flush();
        javafx.application.Platform.exit();
      }
    });
    System.out.println("PROBE setQuitHandler=" + installed);
    if (!installed.startsWith("ok")) {
      System.out.flush();
      System.exit(4);
    }

    System.out.println("READY");
    System.out.flush();

    // Parked the way GanttProject parks: `MainApplication` only records the flag and the real
    // System.exit(0) sits after launch(), which returns only once the platform has exited.
    if (quitRequested.await(120, TimeUnit.SECONDS)) {
      // Reached only if performQuit() did NOT end the process. Kept alive afterwards on purpose,
      // so that the outside observer sees a living process rather than a race.
      Thread.sleep(30_000);
      System.out.println("PROBE still alive 30s after the quit handler ran");
      System.out.flush();
      System.exit(5);
    }
    System.out.println("PROBE no quit request arrived within 120s");
    System.out.flush();
    System.exit(6);
  }

  /**
   * @return "ok" when the handler is installed, otherwise what stood in the way. Never throws:
   * which exception comes out of here is the finding, and a stack trace on stderr would be read as
   * a broken probe rather than as a measured property of the machine.
   */
  private static String tryToInstall(Runnable onQuit) {
    try {
      Desktop.getDesktop().setQuitHandler((quitEvent, response) -> {
        onQuit.run();
        // THE LINE UNDER TEST. DesktopIntegration.maybeQuit does exactly this and nothing more.
        response.performQuit();
      });
      return "ok";
    } catch (Throwable e) {
      return e.getClass().getName() + ": " + e.getMessage();
    }
  }
}
