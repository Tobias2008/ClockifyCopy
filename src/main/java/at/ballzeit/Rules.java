package at.ballzeit;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class Rules {
  private Rules() {}

  public static WebApplicationException error(int code, String message) {
    return new WebApplicationException(
        Response.status(code).entity(Map.of("error", message)).build());
  }

  public static String text(String value, int min, int max, String label) {
    if (value == null || value.strip().length() < min || value.strip().length() > max)
      throw error(400, label + ": bitte " + min + " bis " + max + " Zeichen eingeben.");
    return value.strip();
  }

  public static String email(String value) {
    String s = text(value, 3, 254, "E-Mail").toLowerCase(Locale.ROOT);
    if (!Pattern.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+", s))
      throw error(400, "Ungültige E-Mail-Adresse.");
    return s;
  }

  public static String schoolCode(String value) {
    String code = text(value, 2, 32, "Schulkürzel").toLowerCase(Locale.ROOT);
    if (!code.matches("[a-z0-9][a-z0-9._-]{1,31}"))
      throw error(
          400, "Schulkürzel: nur Buchstaben a–z, Ziffern, Punkt, Bindestrich und Unterstrich.");
    return code;
  }

  public static String password(String value) {
    if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw error(400, "Passwort: mindestens 12 Zeichen und höchstens 72 UTF-8-Bytes.");
    return value;
  }

  public static String uuid(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (Exception e) {
      throw error(400, "Ungültige Kennung.");
    }
  }

  public static void interval(long start, long end, long now) {
    if (start < 0 || end <= start || end > now)
      throw error(400, "Ende muss nach Beginn liegen; zukünftige Zeiten sind nicht erlaubt.");
  }

  public static boolean overlaps(long a, long b, long c, long d) {
    return a < d && c < b;
  }

  public static long localTime(String value, String offset) {
    try {
      var local = LocalDateTime.parse(value);
      var zone = ZoneId.of("Europe/Vienna");
      var offsets = zone.getRules().getValidOffsets(local);
      if (offsets.isEmpty())
        throw error(400, "Diese Uhrzeit existiert wegen der Zeitumstellung nicht.");
      ZoneOffset chosen = offset == null || offset.isBlank() ? null : ZoneOffset.of(offset);
      if (chosen == null && offsets.size() > 1)
        throw error(400, "Doppelte Uhrzeit bei Winterzeit: bitte +02:00 oder +01:00 auswählen.");
      if (chosen == null) chosen = offsets.getFirst();
      if (!offsets.contains(chosen))
        throw error(400, "UTC-Offset passt nicht zur Wiener Ortszeit.");
      return local.toInstant(chosen).toEpochMilli();
    } catch (WebApplicationException e) {
      throw e;
    } catch (Exception e) {
      throw error(400, "Ungültiges Datum oder Uhrzeit.");
    }
  }

  public static String csv(Object value) {
    String s = value == null ? "" : value.toString();
    String leading = s.stripLeading();
    // Inspect the first character rather than a regex dot: formula text may contain newlines.
    if ((!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)
        || s.startsWith("\t")
        || s.startsWith("\r")
        || s.startsWith("\n")) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  public static void version(Map<String, Object> row, Long version) {
    if (version == null || Db.num(row, "version") != version)
      throw error(409, "Zwischenzeitlich geändert. Bitte neu laden und erneut prüfen.");
  }
}
