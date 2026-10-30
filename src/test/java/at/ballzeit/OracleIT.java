package at.ballzeit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** Runs ONLY via mvn verify -DskipITs=false against a dedicated, disposable Oracle schema. */
@QuarkusTest
class OracleIT {
  @Inject Db db;
  @Inject MockMailbox mailbox;
  final String owner = "10000000-0000-0000-0000-000000000001",
      admin = "10000000-0000-0000-0000-000000000002",
      member = "10000000-0000-0000-0000-000000000003",
      other = "10000000-0000-0000-0000-000000000004";
  final String team = "20000000-0000-0000-0000-000000000001",
      team2 = "20000000-0000-0000-0000-000000000002",
      activity = "30000000-0000-0000-0000-000000000001",
      activity2 = "30000000-0000-0000-0000-000000000002";
  static final String PASSWORD = "Nur-im-Test-" + Security.token();
  static final String HASH = Security.passwordHash(PASSWORD);
  Map<String, String> tokens = new HashMap<>(), csrf = new HashMap<>();

  @BeforeEach
  void fixture() {
    if (!"true".equals(System.getenv("TEST_DESTRUCTIVE")))
      throw new IllegalStateException(
          "TEST_DESTRUCTIVE=true nur für ein wegwerfbares Oracle-Testschema setzen!");
    mailbox.clear();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String table :
                  List.of(
                      "audit_events",
                      "sessions",
                      "account_tokens",
                      "time_entries",
                      "members",
                      "activities",
                      "teams",
                      "rate_limits")) db.update("DELETE FROM " + table);
              db.update("UPDATE app_lock SET bootstrapped=0 WHERE id=1");
              db.update(
                  "UPDATE project_settings SET"
                      + " project_name='Testprojekt',preset='MATURABALL',weekly_goal_minutes=300,version=0"
                      + " WHERE id=1");
              db.update("INSERT INTO teams(id,name) VALUES(?,'Ballkomitee')", team);
              db.update("INSERT INTO teams(id,name) VALUES(?,'Klasse 5B')", team2);
              db.update(
                  "INSERT INTO activities(id,name,color) VALUES(?,'Sponsoring','#245c4b')",
                  activity);
              db.update(
                  "INSERT INTO activities(id,name,color) VALUES(?,'Deko','#aabb66')", activity2);
              for (String id : List.of(owner, admin, member, other)) {
                String role = id.equals(owner) ? "OWNER" : id.equals(admin) ? "ADMIN" : "MEMBER";
                db.update(
                    "INSERT INTO"
                        + " members(id,email,display_name,school_code,password_hash,role,team_id)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    id,
                    id + "@example.at",
                    role + id.substring(35),
                    "test-" + id.substring(35),
                    HASH,
                    role,
                    team);
                String token = Security.token(), c = Security.token();
                tokens.put(id, token);
                csrf.put(id, c);
                db.update(
                    "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,?)",
                    Security.hash(token),
                    id,
                    c,
                    System.currentTimeMillis() + 3600000);
              }
            });
  }

  RequestSpecification as(String who) {
    return given()
        .contentType("application/json")
        .header("Origin", "http://localhost:8080")
        .header("X-CSRF-Token", csrf.get(who))
        .cookie("ballzeit_session", tokens.get(who));
  }

  RequestSpecification pub() {
    return given().contentType("application/json").header("Origin", "http://localhost:8080");
  }

  Map<String, Object> body(String start, String end) {
    var b = new HashMap<String, Object>();
    b.put("teamId", team);
    b.put("activityId", activity);
    b.put("description", "Sponsoren für den Ball kontaktiert");
    b.put("start", start);
    b.put("end", end);
    b.put("requestKey", Db.id());
    return b;
  }

  Map<String, Object> entry(String who) {
    return as(who)
        .body(body("2026-01-01T10:00", "2026-01-01T12:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);
  }

  void decide(String who, Map<String, Object> e, String status, int code) {
    as(who)
        .body(
            Map.of(
                "ids",
                List.of(e.get("id")),
                "versions",
                Map.of(e.get("id"), e.get("version")),
                "status",
                status,
                "reason",
                "Beschreibung unzureichend"))
        .post("/api/time/decisions")
        .then()
        .statusCode(code);
  }

  void sql(String statement, Object... args) {
    QuarkusTransaction.requiringNew().run(() -> db.update(statement, args));
  }

  String invitedToken() {
    String text = mailbox.getMailsSentTo("neu@example.at").getLast().getText();
    return text.split("#einladung=")[1].split("\\s")[0];
  }

  void invite() {
    as(admin)
        .body(Map.of("email", "neu@example.at"))
        .post("/api/auth/invite")
        .then()
        .statusCode(200);
  }

  RequestSpecification accept(String token) {
    return pub()
        .body(
            Map.of(
                "token",
                token,
                "displayName",
                "Neues Mitglied",
                "schoolCode",
                "neu.test",
                "password",
                PASSWORD));
  }

  @Test
  void loginLogoutAndNoSecrets() {
    pub()
        .body(Map.of("email", member + "@example.at", "password", PASSWORD))
        .post("/api/auth/login")
        .then()
        .statusCode(200)
        .header("Set-Cookie", allOf(containsString("HttpOnly"), containsString("SameSite=Strict")));
    as(member)
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("password_hash", nullValue())
        .body("email", nullValue());
    as(member).post("/api/auth/logout").then().statusCode(200);
    as(member).get("/api/auth/me").then().statusCode(401);
  }

  @Test
  void unauthorizedAndCsrf() {
    given().get("/api/time/mine").then().statusCode(401);
    given()
        .contentType("application/json")
        .cookie("ballzeit_session", tokens.get(member))
        .body(body("2026-01-01T10:00", "2026-01-01T12:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(403);
    as(member)
        .header("Origin", "https://evil.example")
        .post("/api/auth/logout")
        .then()
        .statusCode(403);
    as(member).get("/api/admin/members").then().statusCode(403);
    as(member).get("/api/catalog").then().body("people[0].email", nullValue());
  }

  @Test
  void cannotEditForeignEntry() {
    var e = entry(other);
    var b = body("2026-01-01T10:00", "2026-01-01T12:00");
    b.put("version", 0);
    as(member).body(b).put("/api/time/entries/" + e.get("id")).then().statusCode(403);
  }

  @Test
  void manualIdempotencyAndOverlap() {
    var b = body("2026-01-01T23:00", "2026-01-02T01:00");
    String id =
        as(member).body(b).post("/api/time/entries").then().statusCode(200).extract().path("id");
    as(member).body(b).post("/api/time/entries").then().statusCode(200).body("id", equalTo(id));
    as(member)
        .body(body("2026-01-02T00:00", "2026-01-02T02:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(409);
    as(member)
        .body(body("2026-01-02T01:00", "2026-01-02T02:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(200);
  }

  @Test
  void invalidFutureAndBlankTimes() {
    for (var b :
        List.of(
            body("2026-01-01T12:00", "2026-01-01T10:00"),
            body("2099-01-01T10:00", "2099-01-01T12:00"),
            body("2026-03-29T02:30", "2026-03-29T04:00")))
      as(member).body(b).post("/api/time/entries").then().statusCode(400);
    var b = body("2026-01-01T10:00", "2026-01-01T12:00");
    b.put("description", "  ");
    as(member).body(b).post("/api/time/entries").then().statusCode(400);
  }

  @Test
  void parallelTimersAndPersistence() throws Exception {
    var b = body(null, null);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.of(
              executor.submit(() -> as(member).body(b).post("/api/time/timer").statusCode()),
              executor.submit(
                  () -> as(member).body(body(null, null)).post("/api/time/timer").statusCode()));
      var codes = List.of(jobs.get(0).get(), jobs.get(1).get());
      assertTrue(codes.contains(200));
      assertTrue(codes.contains(409));
    }
    String id = as(member).get("/api/time/timer").then().statusCode(200).extract().path("timer.id");
    as(member).get("/api/time/timer").then().body("timer.id", equalTo(id));
    var timer = as(member).get("/api/time/timer").jsonPath().getMap("timer");
    b.put("version", timer.get("version"));
    as(member)
        .body(b)
        .post("/api/time/timer/" + id + "/stop")
        .then()
        .statusCode(200)
        .body("status", equalTo("PENDING"));
    as(member).body(b).post("/api/time/timer/" + id + "/stop").then().statusCode(200);
    as(member).get("/api/time/mine").then().body("size()", equalTo(1));
  }

  @Test
  void runningTimerOverlapsManual() {
    var b = body(null, null);
    as(member).body(b).post("/api/time/timer").then().statusCode(200);
    var start = java.time.Instant.now().minusSeconds(3600).toEpochMilli();
    sql("UPDATE time_entries SET started_at=? WHERE user_id=?", start, member);
    var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Vienna"));
    as(member)
        .body(body(now.minusMinutes(30).toString(), now.minusMinutes(15).toString()))
        .post("/api/time/entries")
        .then()
        .statusCode(409);
  }

  @Test
  void approvalAndSelfReview() {
    var e = entry(admin);
    decide(admin, e, "APPROVED", 200);
    as(member)
        .get("/api/time/overview")
        .then()
        .body("durationMillis", equalTo(7200000))
        .body("entries[0].reviewer_name", notNullValue());
  }

  @Test
  void ownApprovalsRemainAuditedAndResetAfterEditing() {
    for (String reviewer : List.of(admin, owner)) {
      var e = entry(reviewer);
      decide(reviewer, e, "APPROVED", 200);
      var saved = db.one("SELECT * FROM time_entries WHERE id=?", e.get("id"));
      assertEquals(reviewer, saved.get("decided_by"));
      assertNotNull(saved.get("decided_at"));
      as(reviewer)
          .get("/api/time/entries/" + e.get("id") + "/history")
          .then()
          .statusCode(200)
          .body("[0].actor_id", equalTo(reviewer));
      var edit = body("2026-01-01T10:00", "2026-01-01T13:00");
      edit.put("version", 1);
      var updated =
          as(reviewer)
              .body(edit)
              .put("/api/time/entries/" + e.get("id"))
              .then()
              .statusCode(200)
              .body("status", equalTo("PENDING"))
              .body("decided_by", nullValue())
              .extract()
              .as(Map.class);
      decide(reviewer, updated, "APPROVED", 200);
      decide(reviewer, updated, "APPROVED", 409);
    }
  }

  @Test
  void memberCannotReviewAndDenialNeedsReason() {
    var e = entry(member);
    decide(member, e, "APPROVED", 403);
    as(admin)
        .body(
            Map.of(
                "ids",
                List.of(e.get("id")),
                "versions",
                Map.of(e.get("id"), 0),
                "status",
                "DENIED",
                "reason",
                ""))
        .post("/api/time/decisions")
        .then()
        .statusCode(400);
    decide(admin, e, "DENIED", 200);
    as(member).get("/api/time/overview").then().body("durationMillis", equalTo(0));
    as(member)
        .get("/api/time/overview?status=DENIED")
        .then()
        .body("durationMillis", equalTo(7200000));
  }

  @Test
  void editResetsReviewAndKeepsHistory() {
    var e = entry(member);
    decide(admin, e, "APPROVED", 200);
    var b = body("2026-01-01T10:00", "2026-01-01T13:00");
    b.put("version", 1);
    as(owner)
        .body(b)
        .put("/api/time/entries/" + e.get("id"))
        .then()
        .statusCode(200)
        .body("status", equalTo("PENDING"))
        .body("decided_by", nullValue());
    as(admin)
        .get("/api/time/entries/" + e.get("id") + "/history")
        .then()
        .body("size()", equalTo(3));
    as(owner).get("/api/time/entries/" + e.get("id") + "/history").then().statusCode(200);
    as(member).get("/api/time/entries/" + e.get("id") + "/history").then().statusCode(403);
    as(other).get("/api/time/entries/" + e.get("id") + "/history").then().statusCode(403);
    as(member).get("/api/admin/history").then().statusCode(403);
    as(member).body(b).put("/api/time/entries/" + e.get("id")).then().statusCode(409);
  }

  @Test
  void combinedFiltersAndHistoricalTeam() {
    var e = entry(member);
    decide(admin, e, "APPROVED", 200);
    sql("UPDATE members SET team_id=? WHERE id=?", team2, member);
    as(member)
        .get(
            "/api/time/overview?status=APPROVED&person="
                + member
                + "&team="
                + team
                + "&activity="
                + activity
                + "&from=2026-01-01&to=2026-01-01")
        .then()
        .body("durationMillis", equalTo(7200000))
        .body("people[0].durationMillis", equalTo(7200000))
        .body("activities[0].durationMillis", equalTo(7200000));
    as(member).get("/api/time/overview?team=" + team2).then().body("durationMillis", equalTo(0));
    as(member)
        .get("/api/time/export?team=" + team + "&status=APPROVED")
        .then()
        .statusCode(200)
        .body(containsString("APPROVED"));
  }

  @Test
  void invitationSingleUseAndNormalizedEmail() {
    invite();
    String token = invitedToken();
    accept(token).post("/api/auth/accept").then().statusCode(200);
    accept(token).post("/api/auth/accept").then().statusCode(400);
    as(admin)
        .body(Map.of("email", " NEU@EXAMPLE.AT "))
        .post("/api/auth/invite")
        .then()
        .statusCode(409);
    assertNull(db.one("SELECT id FROM account_tokens WHERE token_hash=?", token));
  }

  @Test
  void invitationExpiryRevocationAndResend() {
    invite();
    String first = invitedToken();
    invite();
    accept(first).post("/api/auth/accept").then().statusCode(400);
    String second = invitedToken();
    String id =
        Db.str(
            db.one("SELECT id FROM account_tokens WHERE token_hash=?", Security.hash(second)),
            "id");
    as(admin).post("/api/auth/invitations/" + id + "/revoke").then().statusCode(200);
    accept(second).post("/api/auth/accept").then().statusCode(400);
    invite();
    String third = invitedToken();
    sql("UPDATE account_tokens SET expires_at=1 WHERE token_hash=?", Security.hash(third));
    accept(third).post("/api/auth/accept").then().statusCode(400);
  }

  @Test
  void resetSingleUseAndSessionRevocation() {
    String email = member + "@example.at";
    pub().body(Map.of("email", email)).post("/api/auth/reset-request").then().statusCode(200);
    String raw =
        mailbox
            .getMailsSentTo(email)
            .getFirst()
            .getText()
            .split("#passwort-reset=")[1]
            .split("\\s")[0];
    pub()
        .body(Map.of("token", raw, "password", PASSWORD))
        .post("/api/auth/reset")
        .then()
        .statusCode(200);
    pub()
        .body(Map.of("token", raw, "password", PASSWORD))
        .post("/api/auth/reset")
        .then()
        .statusCode(400);
    as(member).get("/api/auth/me").then().statusCode(401);
    pub()
        .body(Map.of("email", "unknown@example.at"))
        .post("/api/auth/reset-request")
        .then()
        .statusCode(200);
  }

  @Test
  void rolesDeactivationAndLastOwner() {
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Owner",
                "teamId",
                team,
                "role",
                "MEMBER",
                "active",
                true,
                "version",
                0))
        .put("/api/admin/members/" + owner)
        .then()
        .statusCode(409);
    as(admin)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "teamId",
                team,
                "role",
                "ADMIN",
                "active",
                true,
                "version",
                0))
        .put("/api/admin/members/" + member)
        .then()
        .statusCode(403);
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "teamId",
                team,
                "role",
                "MEMBER",
                "active",
                false,
                "version",
                0))
        .put("/api/admin/members/" + member)
        .then()
        .statusCode(200);
    as(member).get("/api/auth/me").then().statusCode(401);
    pub()
        .body(Map.of("email", member + "@example.at", "password", PASSWORD))
        .post("/api/auth/login")
        .then()
        .statusCode(401);
  }

  @Test
  void archivedAssignmentsAndConstraints() {
    var e = entry(member);
    sql("UPDATE activities SET active=0 WHERE id=?", activity);
    as(member)
        .body(body("2026-01-02T10:00", "2026-01-02T12:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(400);
    as(member).get("/api/time/mine").then().body("[0].activity_name", equalTo("Sponsoring"));
    assertThrows(Exception.class, () -> sql("DELETE FROM activities WHERE id=?", activity));
  }

  @Test
  void loginRateLimitSurvivesRollback() {
    for (int i = 0; i < 10; i++)
      pub()
          .body(Map.of("email", "wrong@example.at", "password", "wrong"))
          .post("/api/auth/login")
          .then()
          .statusCode(401);
    pub()
        .body(Map.of("email", "wrong@example.at", "password", "wrong"))
        .post("/api/auth/login")
        .then()
        .statusCode(429);
  }

  @Test
  void bootstrapIsOneTimeEvenAfterOwnerDisappears() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              db.update("DELETE FROM sessions");
              db.update("DELETE FROM members");
            });
    var b =
        Map.of(
            "email",
            "first@example.at",
            "displayName",
            "Erster Owner",
            "password",
            PASSWORD,
            "schoolCode",
            "erster.owner",
            "projectName",
            "Unser Jahresprojekt",
            "preset",
            "JAHRESPROJEKT");
    String key =
        org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("ballzeit.bootstrap-key", String.class);
    pub()
        .header("X-Bootstrap-Key", "falsch")
        .body(b)
        .post("/api/auth/bootstrap")
        .then()
        .statusCode(403);
    pub().header("X-Bootstrap-Key", key).body(b).post("/api/auth/bootstrap").then().statusCode(200);
    assertNull(db.one("SELECT team_id FROM members WHERE email='first@example.at'").get("team_id"));
    assertEquals(
        "JAHRESPROJEKT",
        Db.str(db.one("SELECT preset FROM project_settings WHERE id=1"), "preset"));
    pub().header("X-Bootstrap-Key", key).body(b).post("/api/auth/bootstrap").then().statusCode(409);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              db.update("DELETE FROM audit_events");
              db.update("DELETE FROM members");
            });
    pub().header("X-Bootstrap-Key", key).body(b).post("/api/auth/bootstrap").then().statusCode(409);
  }

  @Test
  void simultaneousManualEntriesCannotOverlap() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () ->
                  as(member)
                      .body(body("2026-01-01T10:00", "2026-01-01T12:00"))
                      .post("/api/time/entries")
                      .statusCode());
      var b =
          executor.submit(
              () ->
                  as(member)
                      .body(body("2026-01-01T11:00", "2026-01-01T13:00"))
                      .post("/api/time/entries")
                      .statusCode());
      assertEquals(Set.of(200, 409), Set.of(a.get(), b.get()));
    }
  }

  @Test
  void editingMayNotCreateOverlap() {
    var e = entry(member);
    as(member)
        .body(body("2026-01-01T13:00", "2026-01-01T14:00"))
        .post("/api/time/entries")
        .then()
        .statusCode(200);
    var b = body("2026-01-01T11:00", "2026-01-01T13:30");
    b.put("version", 0);
    as(admin).body(b).put("/api/time/entries/" + e.get("id")).then().statusCode(409);
  }

  @Test
  void deactivationFinishesRunningTimerAndRevokesAccess() {
    as(member).body(body(null, null)).post("/api/time/timer").then().statusCode(200);
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "teamId",
                team,
                "role",
                "MEMBER",
                "active",
                false,
                "version",
                0))
        .put("/api/admin/members/" + member)
        .then()
        .statusCode(200);
    assertEquals(
        "PENDING",
        Db.str(db.one("SELECT status FROM time_entries WHERE user_id=?", member), "status"));
    as(member).get("/api/time/timer").then().statusCode(401);
  }

  @Test
  void resetExpiryAndResend() {
    String email = member + "@example.at";
    pub().body(Map.of("email", email)).post("/api/auth/reset-request").then().statusCode(200);
    String first =
        mailbox
            .getMailsSentTo(email)
            .getLast()
            .getText()
            .split("#passwort-reset=")[1]
            .split("\\s")[0];
    pub().body(Map.of("email", email)).post("/api/auth/reset-request").then().statusCode(200);
    String second =
        mailbox
            .getMailsSentTo(email)
            .getLast()
            .getText()
            .split("#passwort-reset=")[1]
            .split("\\s")[0];
    pub()
        .body(Map.of("token", first, "password", PASSWORD))
        .post("/api/auth/reset")
        .then()
        .statusCode(400);
    sql("UPDATE account_tokens SET expires_at=1 WHERE token_hash=?", Security.hash(second));
    pub()
        .body(Map.of("token", second, "password", PASSWORD))
        .post("/api/auth/reset")
        .then()
        .statusCode(400);
  }

  @Test
  void oracleUniqueTimerConstraint() {
    as(member).body(body(null, null)).post("/api/time/timer").then().statusCode(200);
    assertThrows(
        Exception.class,
        () ->
            sql(
                "INSERT INTO"
                    + " time_entries(id,user_id,team_id,activity_id,description,started_at,status,request_key)"
                    + " VALUES(?,?,?,?,?,?,'RUNNING',?)",
                Db.id(),
                member,
                team,
                activity,
                "Zweiter Timer",
                System.currentTimeMillis(),
                Db.id()));
  }

  @Test
  void newAccountsRequireUniqueSchoolCodeAndStartWithoutTeam() {
    invite();
    String token = invitedToken();
    pub()
        .body(Map.of("token", token, "displayName", "Neu", "password", PASSWORD))
        .post("/api/auth/accept")
        .then()
        .statusCode(400);
    pub()
        .body(
            Map.of(
                "token", token, "displayName", "Neu", "schoolCode", "test-3", "password", PASSWORD))
        .post("/api/auth/accept")
        .then()
        .statusCode(409);
    pub()
        .body(
            Map.of(
                "token",
                token,
                "displayName",
                "Neu",
                "schoolCode",
                "  Neu.Test  ",
                "password",
                PASSWORD))
        .post("/api/auth/accept")
        .then()
        .statusCode(200);
    var user = db.one("SELECT team_id,school_code FROM members WHERE email='neu@example.at'");
    assertNull(user.get("team_id"));
    assertEquals("neu.test", user.get("school_code"));
  }

  @Test
  void onlyAdministrationCreatesAndAssignsTeams() {
    as(member)
        .body(Map.of("name", "Nicht erlaubt"))
        .post("/api/admin/teams")
        .then()
        .statusCode(403);
    sql("UPDATE members SET team_id=NULL WHERE id=?", member);
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "schoolCode",
                "test-3",
                "teamId",
                team2,
                "role",
                "MEMBER",
                "active",
                true,
                "version",
                0))
        .put("/api/admin/members/" + member)
        .then()
        .statusCode(200);
    assertEquals(team2, db.one("SELECT team_id FROM members WHERE id=?", member).get("team_id"));
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "schoolCode",
                "test-3",
                "teamId",
                "",
                "role",
                "MEMBER",
                "active",
                true,
                "version",
                1))
        .put("/api/admin/members/" + member)
        .then()
        .statusCode(200);
    assertNull(db.one("SELECT team_id FROM members WHERE id=?", member).get("team_id"));
    as(member)
        .body(
            Map.of(
                "displayName",
                "Andere Person",
                "schoolCode",
                "test-4",
                "teamId",
                team,
                "role",
                "MEMBER",
                "active",
                true,
                "version",
                0))
        .put("/api/admin/members/" + other)
        .then()
        .statusCode(403);
  }

  void preset(String id) {
    long version = Db.num(db.one("SELECT version FROM project_settings WHERE id=1"), "version");
    as(owner)
        .body(
            Map.of(
                "projectName",
                "Testprojekt",
                "preset",
                id,
                "weeklyGoalMinutes",
                300,
                "version",
                version))
        .put("/api/project")
        .then()
        .statusCode(200);
  }

  @Test
  void appearanceSupportsAllEightCombinationsAndPersists() {
    as(member)
        .get("/api/auth/me")
        .then()
        .body("color_preset", equalTo("PURPLE"))
        .body("color_mode", equalTo("LIGHT"));
    long version = 0;
    for (String color : List.of("PURPLE", "GREEN", "BLUE", "RED"))
      for (String mode : List.of("LIGHT", "DARK")) {
        as(member)
            .body(Map.of("colorPreset", color, "colorMode", mode, "version", version++))
            .put("/api/auth/appearance")
            .then()
            .statusCode(200)
            .body("color_preset", equalTo(color))
            .body("color_mode", equalTo(mode));
      }
    as(member)
        .get("/api/auth/me")
        .then()
        .body("color_preset", equalTo("RED"))
        .body("color_mode", equalTo("DARK"));
    as(member)
        .body(Map.of("colorPreset", "GREEN", "colorMode", "DARK", "version", 0))
        .put("/api/auth/appearance")
        .then()
        .statusCode(409);
    as(member)
        .body(Map.of("colorPreset", "UNSAFE", "colorMode", "DARK", "version", 8))
        .put("/api/auth/appearance")
        .then()
        .statusCode(400);
  }

  @Test
  void fixedTeamAndFreelyEnteredWorkAreEnforced() {
    var b = body("2026-01-01T10:00", "2026-01-01T12:00");
    b.remove("teamId");
    b.remove("activityId");
    b.put("workLabel", "Social Media");
    as(member)
        .body(b)
        .post("/api/time/entries")
        .then()
        .statusCode(200)
        .body("team_id", equalTo(team));
    as(member).get("/api/time/mine").then().body("[0].activity_name", equalTo("Social Media"));
    b.put("requestKey", Db.id());
    b.put("teamId", team2);
    as(member).body(b).post("/api/time/entries").then().statusCode(403);
    b.remove("teamId");
    b.put("start", "2026-01-02T10:00");
    b.put("end", "2026-01-02T12:00");
    b.put("workLabel", "social media");
    as(member).body(b).post("/api/time/entries").then().statusCode(200);
    assertEquals(
        1L,
        Db.num(
            db.one("SELECT COUNT(*) total FROM activities WHERE LOWER(name)='social media'"),
            "total"));
  }

  @Test
  void teamRequiredUntilAdminAssignsIt() {
    sql("UPDATE members SET team_id=NULL WHERE id=?", member);
    var b = body("2026-01-01T10:00", "2026-01-01T12:00");
    b.remove("teamId");
    as(member).body(b).post("/api/time/entries").then().statusCode(409);
  }

  @Test
  void freeProjectActivatesDirectRecordingWithoutTeam() {
    preset("FREIES_PROJEKT");
    sql("UPDATE members SET team_id=NULL WHERE id=?", member);
    as(member)
        .get("/api/project")
        .then()
        .body("features.teams", equalTo(false))
        .body("features.reviews", equalTo(false))
        .body("features.goals", equalTo(true));
    var b = body("2026-01-01T10:00", "2026-01-01T12:00");
    b.remove("teamId");
    b.remove("activityId");
    b.put("workLabel", "Recherche");
    var e =
        as(member)
            .body(b)
            .post("/api/time/entries")
            .then()
            .statusCode(200)
            .body("team_id", nullValue())
            .body("status", equalTo("APPROVED"))
            .body("approval_required", equalTo(0))
            .body("decided_by", nullValue())
            .extract()
            .as(Map.class);
    as(member)
        .get("/api/time/overview")
        .then()
        .body("durationMillis", equalTo(7200000))
        .body("teams[0].name", equalTo("Ohne Team"));
    b.put("version", 0);
    b.put("end", "2026-01-01T13:00");
    as(member)
        .body(b)
        .put("/api/time/entries/" + e.get("id"))
        .then()
        .statusCode(200)
        .body("status", equalTo("APPROVED"));
    preset("MATURABALL");
    b.put("version", 1);
    b.put("end", "2026-01-01T14:00");
    as(owner)
        .body(b)
        .put("/api/time/entries/" + e.get("id"))
        .then()
        .statusCode(200)
        .body("status", equalTo("PENDING"))
        .body("approval_required", equalTo(1));
  }

  @Test
  void changingPresetDoesNotDiscardExistingReviewRequirements() {
    var e = entry(member);
    decide(admin, e, "APPROVED", 200);
    preset("VEREIN");
    var b = body("2026-01-01T10:00", "2026-01-01T13:00");
    b.put("version", 1);
    as(member)
        .body(b)
        .put("/api/time/entries/" + e.get("id"))
        .then()
        .statusCode(200)
        .body("status", equalTo("PENDING"));
    as(owner).get("/api/project").then().body("pendingReviews", equalTo(1));
    as(member).get("/api/time/mine").then().body("[0].team_id", equalTo(team));
  }

  @Test
  void projectChangesRequireOwnerAndCorrectVersion() {
    var b =
        Map.of(
            "projectName",
            "Schulprojekt",
            "preset",
            "JAHRESPROJEKT",
            "weeklyGoalMinutes",
            600,
            "version",
            0);
    as(member).body(b).put("/api/project").then().statusCode(403);
    as(admin).body(b).put("/api/project").then().statusCode(403);
    as(owner)
        .body(b)
        .put("/api/project")
        .then()
        .statusCode(200)
        .body("features.goals", equalTo(true));
    as(owner).body(b).put("/api/project").then().statusCode(409);
    given().get("/api/project/presets").then().statusCode(200).body("size()", equalTo(4));
    as(owner).get("/api/project").then().body("project_name", equalTo("Schulprojekt"));
  }

  @Test
  void allStatusIncludesRunningButKeepsDurationsSeparate() {
    var approved = entry(member);
    decide(admin, approved, "APPROVED", 200);
    var denied = entry(other);
    decide(admin, denied, "DENIED", 200);
    entry(admin);
    as(owner).body(body(null, null)).post("/api/time/timer").then().statusCode(200);
    as(member)
        .get("/api/time/overview?status=ALL")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(4))
        .body("durationMillis", equalTo(21600000))
        .body("completedCount", equalTo(3))
        .body("statuses[0].durationMillis", equalTo(7200000))
        .body("statuses[1].durationMillis", equalTo(7200000))
        .body("statuses[2].durationMillis", equalTo(7200000))
        .body("statuses[3].durationMillis", equalTo(0))
        .body("statuses[3].count", equalTo(1));
    as(member)
        .get(
            "/api/time/overview?status=ALL&person="
                + member
                + "&team="
                + team
                + "&activity="
                + activity
                + "&from=2026-01-01&to=2026-01-01")
        .then()
        .body("entries.size()", equalTo(1))
        .body("durationMillis", equalTo(7200000));
    as(member)
        .get("/api/time/export?status=ALL")
        .then()
        .statusCode(200)
        .body(containsString("RUNNING"))
        .body(containsString("APPROVED"))
        .body(containsString("DENIED"));
  }

  @Test
  void statisticsShareExactFilteredRowsAndViennaDates() {
    var e = entry(member);
    decide(admin, e, "APPROVED", 200);
    as(member)
        .get("/api/time/overview?person=" + member)
        .then()
        .statusCode(200)
        .body("daily[0].name", equalTo("2026-01-01"))
        .body("daily[0].durationMillis", equalTo(7200000))
        .body("weekdays[3].durationMillis", equalTo(7200000))
        .body("averageMillis", equalTo(7200000.0f))
        .body("activePeople", equalTo(1));
  }

  @Test
  void directPresetFinishesTimersAndDeactivationWithoutInventingReviewer() {
    preset("VEREIN");
    var b = body(null, null);
    String id =
        as(member)
            .body(b)
            .post("/api/time/timer")
            .then()
            .statusCode(200)
            .body("approval_required", equalTo(0))
            .extract()
            .path("id");
    b.put("version", 0);
    as(member)
        .body(b)
        .post("/api/time/timer/" + id + "/stop")
        .then()
        .statusCode(200)
        .body("status", equalTo("APPROVED"))
        .body("decided_by", nullValue());
    as(other).body(body(null, null)).post("/api/time/timer").then().statusCode(200);
    as(owner)
        .body(
            Map.of(
                "displayName",
                "Mitglied",
                "schoolCode",
                "test-4",
                "teamId",
                team,
                "role",
                "MEMBER",
                "active",
                false,
                "version",
                0))
        .put("/api/admin/members/" + other)
        .then()
        .statusCode(200);
    var row = db.one("SELECT * FROM time_entries WHERE user_id=?", other);
    assertEquals("APPROVED", row.get("status"));
    assertNull(row.get("decided_by"));
  }

  @Test
  void timerTeamSnapshotSurvivesAnAdminTeamChange() {
    var b = body(null, null);
    b.remove("teamId");
    String id =
        as(member).body(b).post("/api/time/timer").then().statusCode(200).extract().path("id");
    sql("UPDATE members SET team_id=? WHERE id=?", team2, member);
    b.put("version", 0);
    as(member)
        .body(b)
        .post("/api/time/timer/" + id + "/stop")
        .then()
        .statusCode(200)
        .body("team_id", equalTo(team));
    var manual = body("2026-01-02T10:00", "2026-01-02T12:00");
    manual.remove("teamId");
    as(member)
        .body(manual)
        .post("/api/time/entries")
        .then()
        .statusCode(200)
        .body("team_id", equalTo(team2));
  }
}
