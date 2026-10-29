package at.ballzeit;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.WebApplicationException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void intervals() {
    Rules.interval(1, 2, 3);
    assertThrows(WebApplicationException.class, () -> Rules.interval(2, 1, 3));
    assertThrows(WebApplicationException.class, () -> Rules.interval(1, 4, 3));
    assertThrows(WebApplicationException.class, () -> Rules.interval(1, 1, 3));
  }

  @Test
  void overlapsAndAdjacent() {
    assertTrue(Rules.overlaps(1, 5, 4, 9));
    assertFalse(Rules.overlaps(1, 5, 5, 9));
    assertTrue(Rules.overlaps(2, 4, 1, 6));
  }

  @Test
  void springGapRejected() {
    assertThrows(WebApplicationException.class, () -> Rules.localTime("2026-03-29T02:30", null));
  }

  @Test
  void autumnNeedsOffset() {
    assertThrows(WebApplicationException.class, () -> Rules.localTime("2026-10-25T02:30", null));
    assertEquals(
        3600000,
        Rules.localTime("2026-10-25T02:30", "+01:00")
            - Rules.localTime("2026-10-25T02:30", "+02:00"));
  }

  @Test
  void offsetValidated() {
    assertThrows(
        WebApplicationException.class, () -> Rules.localTime("2026-01-01T12:00", "+02:00"));
  }

  @Test
  void midnightDuration() {
    assertEquals(
        7200000,
        Rules.localTime("2026-01-02T01:00", null) - Rules.localTime("2026-01-01T23:00", null));
  }

  @Test
  void csvFormulaProtection() {
    for (String s :
        List.of(
            "=HYPERLINK(\"x\")",
            "=SUM(1,\n2)",
            "+1",
            "-2",
            "@SUM(A1)",
            "  =1",
            "\t=1",
            "\r=1",
            "\n=1")) assertTrue(Rules.csv(s).startsWith("\"'"));
    assertEquals("\"a\"\"b\"", Rules.csv("a\"b"));
  }

  @Test
  void normalizationAndLengths() {
    assertEquals("a@example.at", Rules.email(" A@EXAMPLE.AT "));
    assertThrows(WebApplicationException.class, () -> Rules.text("   ", 3, 1000, "Beschreibung"));
    assertThrows(WebApplicationException.class, () -> Rules.password("short"));
    assertThrows(WebApplicationException.class, () -> Rules.password("ä".repeat(37)));
  }

  @Test
  void staleVersion() {
    assertThrows(WebApplicationException.class, () -> Rules.version(Map.of("version", 2), 1L));
    Rules.version(Map.of("version", 2), 2L);
  }

  @Test
  void secureTokensAndHashes() {
    var a = Security.token();
    assertNotEquals(a, Security.token());
    assertEquals(64, Security.hash(a).length());
    assertNotEquals(a, Security.hash(a));
    String p = "Ein sicheres Testpasswort";
    String h = Security.passwordHash(p);
    assertTrue(Security.matches(p, h));
    assertFalse(Security.matches("falsch", h));
    assertNotEquals(h, Security.passwordHash(p));
  }

  @Test
  void exactAggregation() {
    var a = new HashMap<String, Object>();
    a.put("user_id", "one");
    a.put("display_name", "Anna");
    a.put("started_at", 0);
    a.put("ended_at", 1234);
    a.put("color", "#123456");
    var b = new HashMap<>(a);
    b.put("started_at", 2000);
    b.put("ended_at", 3234);
    var result = TimeResource.breakdown(List.of(a, b), "user_id", "display_name");
    assertEquals(2468L, result.getFirst().get("durationMillis"));
  }

  @Test
  void schoolCodeRequiredAndNormalized() {
    assertEquals("t.payreder", Rules.schoolCode(" T.Payreder "));
    for (String value : List.of("", "a", "mit leerzeichen", "a@schule.at", "x".repeat(33)))
      assertThrows(WebApplicationException.class, () -> Rules.schoolCode(value));
    assertThrows(WebApplicationException.class, () -> Rules.schoolCode(null));
  }
}
