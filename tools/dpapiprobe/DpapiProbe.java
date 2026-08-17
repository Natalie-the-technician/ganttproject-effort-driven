import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Prueft, ob DPAPI auf dem AUSGELIEFERTEN Klassenpfad laeuft. jna-platform 5.16 kommt nur
 * mittelbar herein, der Kern jna liegt als 5.13 daneben -- eine Versionsmischung, die erst zur
 * Laufzeit auffaellt. Ohne diesen Lauf waere die Aufwandsschaetzung geraten.
 */
public class DpapiProbe {
  public static void main(String[] args) {
    String geheim = "Passwort-Probe-äöü-123";
    try {
      byte[] chiffre = Crypt32Util.cryptProtectData(geheim.getBytes(StandardCharsets.UTF_8));
      String gespeichert = Base64.getEncoder().encodeToString(chiffre);
      byte[] klar = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(gespeichert));
      String zurueck = new String(klar, StandardCharsets.UTF_8);
      System.out.println("Chiffrelaenge          : " + chiffre.length + " Bytes");
      System.out.println("Base64-Laenge          : " + gespeichert.length() + " Zeichen");
      System.out.println("enthaelt Klartext?     : " + gespeichert.contains("Passwort"));
      System.out.println("Rundlauf gleich?       : " + geheim.equals(zurueck));
      System.out.println(geheim.equals(zurueck) && !gespeichert.contains("Passwort")
          ? "ERGEBNIS=OK" : "ERGEBNIS=FEHLER");
    } catch (Throwable t) {
      System.out.println("ERGEBNIS=FEHLER -- " + t.getClass().getName() + ": " + t.getMessage());
    }
  }
}
