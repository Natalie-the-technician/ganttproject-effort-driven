import io.milton.common.Path;
import io.milton.httpclient.Host;
import io.milton.httpclient.PropFindResponse;

import javax.xml.namespace.QName;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
 *
 * Prueft an einem echten WebDAV-Server genau eine Frage: liefert Miltons PROPFIND den ETag?
 *
 * Warum das kein Einheitstest sein kann: die Antwort haengt an der Bibliothek UND am Server, und
 * genau in deren Zusammenspiel lag der Fehler. Milton fragt von sich aus nur eine feste
 * Eigenschaftsliste ab, in der getetag fehlt; {@code File.getEtag()} lieferte deshalb immer null
 * und das bedingte Schreiben fiel still auf "bedingungslos" zurueck. Ein Test mit erfundenen
 * Antworten haette das nie gezeigt -- er haette die Eingabe selbst gestellt.
 *
 * Die Zugangsdaten liest das Programm aus einer Umgebungsdatei, die NICHT im Repo liegt. Nicht
 * ueber die Befehlszeile: die steht in der Prozessliste jedes anderen Benutzers. Ausgegeben wird
 * nur der ETag, nie ein Zugangsdatum.
 */
public class EtagProbe {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("Aufruf: EtagProbe <umgebungsdatei> [pfad-der-datei]");
      System.out.println("Die Umgebungsdatei enthaelt GP_DAV_BASE, GP_DAV_USER, GP_DAV_PASS.");
      System.exit(2);
    }
    String resourcePath = args.length > 1 ? args[1] : "/haus.gan";

    String base = null, user = null, pass = null;
    try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        int eq = line.indexOf('=');
        if (eq < 0) continue;
        String k = line.substring(0, eq), v = line.substring(eq + 1);
        if (k.equals("GP_DAV_BASE")) base = v;
        else if (k.equals("GP_DAV_USER")) user = v;
        else if (k.equals("GP_DAV_PASS")) pass = v;
      }
    }
    if (base == null) {
      System.out.println("FEHLER: GP_DAV_BASE fehlt in " + args[0]);
      System.exit(2);
    }

    URI uri = new URI(base);
    int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    String rootPath = uri.getPath().replaceAll("/$", "");
    System.out.println("Server=" + uri.getHost() + " Port=" + port + " Wurzel=" + rootPath
        + " Benutzer gesetzt=" + (user != null && !user.isEmpty())
        + " Passwort gesetzt=" + (pass != null && !pass.isEmpty()));

    Host host = new Host(uri.getHost(), rootPath, port, user, pass, null, 30000, null, null);
    host.setSecure("https".equals(uri.getScheme()));

    QName etagProperty = new QName("DAV:", "getetag");

    String found = firstEtag(
        host.propFind(Path.path(resourcePath), 0, Collections.singletonList(etagProperty)));
    System.out.println("MIT angeforderter Eigenschaft : ETag=" + found);

    // Gegenprobe im selben Lauf: ohne die Anforderung MUSS null herauskommen. Kaeme auch hier ein
    // Wert, waere die gefundene Ursache nicht die Ursache.
    String control = firstEtag(
        host.propFind(Path.path(resourcePath), 0, Collections.<QName>emptyList()));
    System.out.println("OHNE angeforderte Eigenschaft : ETag=" + control);

    boolean ok = found != null && control == null;
    System.out.println(ok ? "ERGEBNIS=OK" : "ERGEBNIS=FEHLER");
    System.exit(ok ? 0 : 1);
  }

  /**
   * Milton liefert bei leerer Eigenschaftsliste {@code null} statt einer leeren Liste -- der
   * Gegenprobe-Zweig lief deshalb beim ersten Versuch in eine NullPointerException.
   */
  private static String firstEtag(List<PropFindResponse> responses) {
    return (responses == null || responses.isEmpty()) ? null : responses.get(0).getEtag();
  }
}
