import net.sourceforge.ganttproject.document.webdav.MiltonResourceFactory;
import net.sourceforge.ganttproject.document.webdav.WebDavResource;
import net.sourceforge.ganttproject.document.webdav.WebDavUri;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * NEUE DATEI DIESES FORKS — im Original-GanttProject nicht vorhanden.
 *
 * Prueft an einem echten Server, ob eine Datei nach dem EIGENEN Sperren noch als schreibbar gilt.
 *
 * DER FEHLER, DEN DAS ABFAENGT: Milton setzt beim PROPFIND lockToken und lockOwner gemeinsam, nach
 * einem eigenen lock() aber nur das Token. getLockOwners() liefert dann den Platzhalter
 * "Unknown user", doCanLock meldet LOCK_UNAVAILABLE, und isWritable() ist false -- der Desktop
 * verweigert das Speichern der Datei, die er selbst gerade gesperrt hat. Am Bildschirm sichtbar als
 * "Dokument kann nicht geschrieben werden", ohne dass es je bis zum PUT kommt.
 *
 * Warum das kein Einheitstest ist: der Zustand entsteht erst aus echten Antworten des Servers auf
 * LOCK und PROPFIND. Ein Test mit erfundenen Antworten stellt sich genau die Eingabe selbst, um die
 * es hier geht -- dieselbe Luecke wie bei tools/etagprobe.
 *
 * Es wird NICHT geschrieben. Die Sperre wird am Ende wieder geloest, auch im Fehlerfall.
 */
public class LockProbe {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("Aufruf: LockProbe <umgebungsdatei> [dateiname]");
      System.exit(2);
    }
    String fileName = args.length > 1 ? args[1] : "haus.gan";

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
    String root = base.replaceAll("/$", "");
    System.out.println("Wurzel gesetzt=" + (root != null) + " Benutzer gesetzt=" + (user != null && !user.isEmpty())
        + " Passwort gesetzt=" + (pass != null && !pass.isEmpty()));

    MiltonResourceFactory factory = new MiltonResourceFactory();
    factory.setCredentials(user, pass);
    WebDavResource resource = factory.createResource(new WebDavUri(root + "/" + fileName));

    if (!resource.exists()) {
      System.out.println("FEHLER: " + fileName + " gibt es dort nicht");
      System.exit(2);
    }

    boolean writableBefore = resource.isWritable();
    System.out.println("schreibbar VOR dem Sperren  : " + writableBefore);

    boolean writableAfter;
    resource.lock(120);
    try {
      writableAfter = resource.isWritable();
      System.out.println("schreibbar NACH dem Sperren : " + writableAfter);
    } finally {
      resource.unlock();
      System.out.println("Sperre wieder geloest");
    }

    // Die erste Zeile ist die Gegenprobe: waere schon ohne Sperre false, pruefte dieses Programm
    // etwas anderes als gemeint -- etwa fehlende Schreibrechte.
    boolean ok = writableBefore && writableAfter;
    System.out.println(ok ? "ERGEBNIS=OK" : "ERGEBNIS=FEHLER (der Desktop sperrt sich selbst aus)");
    System.exit(ok ? 0 : 1);
  }
}
