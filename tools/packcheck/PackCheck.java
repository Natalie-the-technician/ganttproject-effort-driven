import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Prueft das Textbuendel unter der ECHTEN Klassenpfadwurzel des gebauten Plugins.
 *
 * Warum ausserhalb der Testsuite: Gegentest 22 hat gezeigt, dass ein Einheitstest das nicht kann.
 * build.gradle meldet zwei Ordner als Wurzel, unter Test antwortet das Buendel deshalb auf beide
 * Pfade. Hier wird als Klassenpfad genau das gesetzt, was plugin.xml zur Bibliothekswurzel macht:
 * der Ordner resources/ des gebauten Plugins. Faellt der Pfad falsch aus, findet dieses Programm
 * nichts -- anders als der Einheitstest.
 */
public class PackCheck {
  public static void main(String[] args) throws Exception {
    int failures = 0;
    failures += check("/language/fork/i18n_de.properties", "fork.toggl.checkConnection",
        "Toggl-Verbindung prüfen");
    failures += check("/language/fork/i18n_de.properties", "fork.toggl.check.title",
        "Toggl-Verbindung");
    failures += check("/language/fork/i18n.properties", "fork.toggl.checkConnection",
        "Check Toggl connection");

    // Konflikttext ausserhalb der Cloud. Der Dialog stellt "fork.webdav.versionMismatch." vor den
    // Schluessel; fehlt der Eintrag, faellt die Anzeige auf den Cloud-Text zurueck und nennt dem
    // Benutzer einen Grund, den es in seinem Fall nicht gibt.
    failures += check("/language/fork/i18n_de.properties", "fork.webdav.versionMismatch.title",
        "Datei wurde von jemand anderem geändert");
    failures += check("/language/fork/i18n.properties", "fork.webdav.versionMismatch.title",
        "File changed by somebody else");

    // Gegenprobe im selben Lauf: unter der Produktionswurzel darf es KEIN resources/resources
    // geben. Faende sich dort etwas, waere der Klassenpfad nicht der, den das Plugin benutzt.
    InputStream wrong = PackCheck.class.getResourceAsStream("/resources/language/fork/i18n.properties");
    if (wrong != null) {
      System.out.println("FEHLER: Buendel auch unter /resources/... erreichbar -- falscher Klassenpfad");
      failures++;
    } else {
      System.out.println("OK    : unter /resources/... nicht erreichbar (erwartet)");
    }

    System.out.println(failures == 0 ? "ERGEBNIS=OK" : "ERGEBNIS=FEHLER(" + failures + ")");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static int check(String path, String key, String expected) throws Exception {
    InputStream in = PackCheck.class.getResourceAsStream(path);
    if (in == null) {
      System.out.println("FEHLER: " + path + " unter der Produktionswurzel nicht gefunden");
      return 1;
    }
    Properties p = new Properties();
    try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    String actual = p.getProperty(key);
    if (expected.equals(actual)) {
      System.out.println("OK    : " + key + " = " + actual);
      return 0;
    }
    System.out.println("FEHLER: " + key + " erwartet <" + expected + "> war <" + actual + ">");
    return 1;
  }
}
