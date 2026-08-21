package net.sourceforge.ganttproject.launcher;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEW FILE IN THIS FORK — not present in the original GanttProject.
 *
 * Main class of the jpackage launcher. Carries the eclipsito arguments itself and appends whatever
 * came from outside.
 *
 * WHY IT EXISTS: jpackage writes the arguments from {@code --arguments} into the .cfg as {@code
 * [ArgOptions]}, but uses them ONLY AS A DEFAULT. As soon as the launcher is called with arguments
 * -- and on a double click on a .gan Windows passes exactly one, the file path -- the defaults are
 * REPLACED completely. eclipsito then only gets a file name, without {@code --app} and without
 * {@code --version-dirs}, aborts, and the launcher reports a blanket
 * "Failed to launch JVM".
 *
 * MEASURED ON THE MACHINE, not guessed:
 * <pre>
 *   Working directory program folder, without argument -> log written, JVM ran
 *   Working directory C:\,             without argument -> log written, JVM ran
 *   Working directory C:\,             WITH .gan       -> no log, "Failed to launch JVM"
 *   Working directory C:\,  full argument list + .gan -> log written, JVM ran
 * </pre>
 * The obvious suspicion -- the working directory -- is thereby refuted: it makes no difference. It
 * is due solely to the argument passed in.
 */
public class ForkLauncher {

  private static final String ECLIPSITO_MAIN = "com.bardsoftware.eclipsito.Launch";
  private static final String APP_MAIN = "net.sourceforge.ganttproject.GanttProject";

  public static void main(String[] args) throws Exception {
    List<String> full = new ArrayList<>(Arrays.asList(
        "--verbosity", "1",
        // Absolute, not relative: eclipsito resolves a relative path against the
        // WORKING DIRECTORY, and on a double click that is the folder of the .gan file.
        "--version-dirs", new File(appDir(), "plugins") + ";~/.ganttproject.d/updates",
        "--app", APP_MAIN,
        // A windowed program has no console. Without the log every failed start is a black
        // box -- this investigation could not have taken place without it.
        "-log", "true"));
    // Everything that came from outside goes at the end: eclipsito passes the arguments after
    // --app through to the application, and there the path of the .gan file is expected.
    full.addAll(Arrays.asList(args));

    Class.forName(ECLIPSITO_MAIN)
        .getMethod("main", String[].class)
        .invoke(null, (Object) full.toArray(new String[0]));
  }

  /**
   * The app folder of the launcher.
   *
   * First via the location of this class itself -- it sits in the app folder, which is registered
   * as a classpath root. That is independent of where the program was started from, and that is
   * exactly what matters here.
   *
   * As a fallback via {@code jpackage.app-path}, the path of the .exe: the app folder is its
   * sibling folder "app". A fallback is needed because the first question can return null if the
   * class was loaded differently -- and without the app folder eclipsito finds no plugins and does
   * nothing at all, silently.
   */
  private static File appDir() {
    try {
      URI location = ForkLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      File file = new File(location);
      // As a directory on the classpath this is the app folder itself, as a jar it is its folder.
      return file.isDirectory() ? file : file.getParentFile();
    } catch (Exception e) {
      String exe = System.getProperty("jpackage.app-path");
      if (exe != null) {
        return new File(new File(exe).getParentFile(), "app");
      }
      throw new IllegalStateException("Der app-Ordner ist nicht zu bestimmen", e);
    }
  }
}
