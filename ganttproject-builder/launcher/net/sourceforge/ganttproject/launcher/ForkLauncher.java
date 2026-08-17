package net.sourceforge.ganttproject.launcher;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
 *
 * Hauptklasse des jpackage-Starters. Bringt die eclipsito-Argumente selbst mit und haengt an, was
 * von aussen kam.
 *
 * WARUM ES SIE GIBT: jpackage schreibt die Argumente aus {@code --arguments} als {@code
 * [ArgOptions]} in die .cfg, benutzt sie aber NUR ALS VORGABE. Sobald der Starter mit Argumenten
 * aufgerufen wird -- und beim Doppelklick auf eine .gan uebergibt Windows genau eines, den
 * Dateipfad -- werden die Vorgaben vollstaendig ERSETZT. eclipsito bekommt dann nur noch einen
 * Dateinamen, ohne {@code --app} und ohne {@code --version-dirs}, bricht ab, und der Starter meldet
 * pauschal "Failed to launch JVM".
 *
 * AM RECHNER GEMESSEN, nicht vermutet:
 * <pre>
 *   Arbeitsverzeichnis Programmordner, ohne Argument -> Protokoll geschrieben, JVM lief
 *   Arbeitsverzeichnis C:\,             ohne Argument -> Protokoll geschrieben, JVM lief
 *   Arbeitsverzeichnis C:\,             MIT .gan      -> kein Protokoll, "Failed to launch JVM"
 *   Arbeitsverzeichnis C:\,  volle Argumentliste + .gan -> Protokoll geschrieben, JVM lief
 * </pre>
 * Die naheliegende Vermutung -- das Arbeitsverzeichnis -- ist damit widerlegt: es ist egal. Es
 * liegt allein am uebergebenen Argument.
 */
public class ForkLauncher {

  private static final String ECLIPSITO_MAIN = "com.bardsoftware.eclipsito.Launch";
  private static final String APP_MAIN = "net.sourceforge.ganttproject.GanttProject";

  public static void main(String[] args) throws Exception {
    List<String> full = new ArrayList<>(Arrays.asList(
        "--verbosity", "1",
        // Absolut, nicht relativ: eclipsito loest einen relativen Pfad gegen das
        // ARBEITSVERZEICHNIS auf, und das ist beim Doppelklick der Ordner der .gan-Datei.
        "--version-dirs", new File(appDir(), "plugins") + ";~/.ganttproject.d/updates",
        "--app", APP_MAIN,
        // Bei einem Fenster-Programm gibt es keine Konsole. Ohne das Protokoll ist jeder
        // Fehlstart eine Blackbox -- diese Fehlersuche haette ohne es nicht stattfinden koennen.
        "-log", "true"));
    // Alles, was von aussen kam, ans Ende: eclipsito reicht die Argumente hinter --app an die
    // Anwendung durch, und dort ist der Dateipfad der .gan-Datei erwartet.
    full.addAll(Arrays.asList(args));

    Class.forName(ECLIPSITO_MAIN)
        .getMethod("main", String[].class)
        .invoke(null, (Object) full.toArray(new String[0]));
  }

  /**
   * Der app-Ordner des Starters.
   *
   * Zuerst ueber den Ort dieser Klasse selbst -- sie liegt im app-Ordner, der als Klassenpfadwurzel
   * eingetragen ist. Das ist unabhaengig davon, wo das Programm gestartet wurde, und genau darauf
   * kommt es hier an.
   *
   * Ersatzweise ueber {@code jpackage.app-path}, den Pfad der .exe: app-Ordner ist deren
   * Nachbarordner "app". Ein Ersatzweg ist noetig, weil die erste Frage null liefern kann, wenn die
   * Klasse anders geladen wurde -- und ohne app-Ordner findet eclipsito keine Plugins und tut
   * stumm gar nichts.
   */
  private static File appDir() {
    try {
      URI location = ForkLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      File file = new File(location);
      // Als Verzeichnis auf dem Klassenpfad ist das direkt der app-Ordner, als Jar dessen Ordner.
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
