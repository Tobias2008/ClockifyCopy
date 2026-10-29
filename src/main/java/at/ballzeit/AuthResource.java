package at.ballzeit;

import io.quarkus.mailer.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {
  @Inject Db db;
  @Inject Security security;
  @Inject Audit audit;
  @Inject Mailer mailer;
  @Inject ProjectResource project;
  @Inject jakarta.transaction.TransactionSynchronizationRegistry transaction;

  @ConfigProperty(name = "ballzeit.public-url")
  String publicUrl;

  @ConfigProperty(name = "ballzeit.bootstrap-key", defaultValue = "")
  String bootstrapKey;

  @ConfigProperty(name = "ballzeit.mail-enabled")
  boolean mailEnabled;

  public record Login(String email, String password) {}

  public record Accept(String token, String displayName, String schoolCode, String password) {}

  public record Reset(String token, String password) {}

  public record Email(String email) {}

  public record Password(String currentPassword, String newPassword) {}

  public record Bootstrap(
      String email,
      String displayName,
      String schoolCode,
      String password,
      String projectName,
      String preset) {}

  public record Invitation(String email) {}

  private static final String NEUTRAL =
      "Falls ein aktives Konto existiert, wurde ein Link angefordert.";
  private final String dummyHash =
      io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(Security.token(), 12);

  public String availableSchoolCode(String value, String except) {
    String code = Rules.schoolCode(value);
    if (db.one(
            "SELECT id FROM members WHERE school_code=? AND id<>?",
            code,
            except == null ? "-" : except)
        != null) throw Rules.error(409, "Dieses Schulkürzel wird bereits verwendet.");
    return code;
  }

  @POST
  @Path("/login")
  @Transactional
  public Response login(Login b) {
    String email = Rules.email(b.email());
    security.limit("login:global", 150);
    security.limit("login:" + email, 10);
    db.lock();
    var user = db.one("SELECT * FROM members WHERE email=?", email);
    boolean valid =
        Security.matches(b.password(), user == null ? dummyHash : Db.str(user, "password_hash"));
    if (!valid || user == null || Db.num(user, "active") != 1)
      throw Rules.error(401, "E-Mail oder Passwort ungültig.");
    db.update("DELETE FROM sessions WHERE expires_at<?", System.currentTimeMillis());
    String token = Security.token();
    db.update(
        "INSERT INTO sessions(token_hash,user_id,csrf,expires_at) VALUES(?,?,?,?)",
        Security.hash(token),
        user.get("id"),
        Security.token(),
        System.currentTimeMillis() + 43200000L);
    return Response.ok(Map.of("message", "Angemeldet"))
        .header("Set-Cookie", security.cookie(token))
        .build();
  }

  @GET
  @Path("/me")
  public Object me(@Context ContainerRequestContext r) {
    return r.getProperty("actor");
  }

  public record Appearance(String colorPreset, String colorMode, Long version) {}

  @PUT
  @Path("/appearance")
  @Transactional
  public Object appearance(Appearance b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    Rules.version(a, b.version());
    if (b.colorPreset() == null
        || !Set.of("PURPLE", "GREEN", "BLUE", "RED").contains(b.colorPreset())
        || b.colorMode() == null
        || !Set.of("LIGHT", "DARK").contains(b.colorMode()))
      throw Rules.error(400, "Ungültige Farbauswahl.");
    db.update(
        "UPDATE members SET color_preset=?,color_mode=?,version=version+1 WHERE id=?",
        b.colorPreset(),
        b.colorMode(),
        a.get("id"));
    return security.actor(r);
  }

  @POST
  @Path("/logout")
  @Transactional
  public Response logout(@Context ContainerRequestContext r) {
    var c = r.getCookies().get("ballzeit_session");
    db.update("DELETE FROM sessions WHERE token_hash=?", Security.hash(c.getValue()));
    return Response.ok(Map.of("message", "Abgemeldet"))
        .header("Set-Cookie", security.cookie(""))
        .build();
  }

  @POST
  @Path("/password")
  @Transactional
  public Response password(Password b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    var u = db.one("SELECT password_hash FROM members WHERE id=?", a.get("id"));
    if (!Security.matches(b.currentPassword(), Db.str(u, "password_hash")))
      throw Rules.error(400, "Aktuelles Passwort ist falsch.");
    db.update(
        "UPDATE members SET password_hash=?,version=version+1 WHERE id=?",
        Security.passwordHash(b.newPassword()),
        a.get("id"));
    db.update("DELETE FROM sessions WHERE user_id=?", a.get("id"));
    db.update(
        "UPDATE account_tokens SET revoked_at=? WHERE email=(SELECT email FROM members WHERE id=?)"
            + " AND kind='RESET' AND used_at IS NULL",
        System.currentTimeMillis(),
        a.get("id"));
    audit.record(Db.str(a, "id"), "PASSWORD_CHANGED", Db.str(a, "id"), Map.of());
    return Response.ok(Map.of("message", "Passwort geändert. Bitte erneut anmelden."))
        .header("Set-Cookie", security.cookie(""))
        .build();
  }

  @POST
  @Path("/bootstrap")
  @Transactional
  public Object bootstrap(Bootstrap b, @HeaderParam("X-Bootstrap-Key") String key) {
    security.limit("bootstrap", 10);
    db.lock();
    if (bootstrapKey.length() < 32
        || key == null
        || !java.security.MessageDigest.isEqual(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            bootstrapKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw Rules.error(403, "Einrichtung nicht freigegeben.");
    if (Db.num(db.one("SELECT bootstrapped FROM app_lock WHERE id=1"), "bootstrapped") != 0
        || !db.list("SELECT id FROM members").isEmpty())
      throw Rules.error(409, "Ersteinrichtung bereits abgeschlossen.");
    String id = Db.id();
    String schoolCode = availableSchoolCode(b.schoolCode(), null);
    db.update(
        "INSERT INTO members(id,email,display_name,school_code,password_hash,role)"
            + " VALUES(?,?,?,?,?,'OWNER')",
        id,
        Rules.email(b.email()),
        Rules.text(b.displayName(), 2, 80, "Anzeigename"),
        schoolCode,
        Security.passwordHash(b.password()));
    project.save(
        b.projectName() == null ? "Maturaball" : b.projectName(),
        b.preset() == null ? "MATURABALL" : b.preset(),
        300);
    db.update("UPDATE app_lock SET bootstrapped=1 WHERE id=1");
    audit.record(id, "BOOTSTRAP", id, Map.of());
    return Map.of("message", "Owner erstellt. Bitte anmelden.");
  }

  private void send(String email, String kind, String token) {
    if (!mailEnabled) throw Rules.error(503, "E-Mail-Versand ist noch nicht konfiguriert.");
    String link =
        publicUrl.replaceAll("/$", "")
            + "/#"
            + (kind.equals("INVITE") ? "einladung" : "passwort-reset")
            + "="
            + token;
    try {
      mailer.send(
          Mail.withText(
              email,
              kind.equals("INVITE")
                  ? "Deine Einladung zu Time Tracker"
                  : "Time Tracker: Passwort zurücksetzen",
              "Hallo!\n\nÖffne diesen einmaligen Link:\n"
                  + link
                  + "\n\nGültigkeit: "
                  + (kind.equals("INVITE") ? "48 Stunden" : "30 Minuten")
                  + ". Falls du dies nicht angefordert hast, ignoriere diese E-Mail.\n\n"
                  + "Dein Time-Tracker-Team"));
    } catch (RuntimeException e) {
      throw Rules.error(
          503, "E-Mail konnte nicht versendet werden. Bitte später erneut versuchen.");
    }
  }

  public void issue(String email, String kind, String team, String actor) {
    String token = Security.token(), id = Db.id();
    long now = System.currentTimeMillis();
    db.update(
        "UPDATE account_tokens SET revoked_at=? WHERE email=? AND kind=? AND used_at IS NULL AND"
            + " revoked_at IS NULL",
        now,
        email,
        kind);
    db.update(
        "INSERT INTO"
            + " account_tokens(id,email,kind,team_id,token_hash,expires_at,created_by,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        email,
        kind,
        team,
        Security.hash(token),
        now + (kind.equals("INVITE") ? 172800000L : 1800000L),
        actor,
        now);
    send(email, kind, token);
    audit.record(actor, kind + "_SENT", id, Map.of("email", email));
  }

  @POST
  @Path("/invite")
  @Transactional
  public Object invite(Invitation b, @Context ContainerRequestContext r) {
    var a = security.admin(r);
    security.limit("invite:" + a.get("id"), 40);
    db.lock();
    security.admin(r);
    String email = Rules.email(b.email());
    if (db.one("SELECT id FROM members WHERE email=?", email) != null)
      throw Rules.error(409, "Für diese Adresse existiert bereits ein Konto.");
    issue(email, "INVITE", null, Db.str(a, "id"));
    return Map.of("message", "Einladung versendet. Frühere Links sind ungültig.");
  }

  @GET
  @Path("/invitations")
  public Object invitations(@Context ContainerRequestContext r) {
    security.admin(r);
    return db.list(
        "SELECT t.id,t.email,t.team_id,t.expires_at,t.used_at,t.revoked_at,t.created_at FROM"
            + " account_tokens t WHERE kind='INVITE' ORDER BY created_at DESC");
  }

  @POST
  @Path("/invitations/{id}/revoke")
  @Transactional
  public Object revoke(@PathParam("id") String id, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.admin(r);
    db.update(
        "UPDATE account_tokens SET revoked_at=? WHERE id=? AND kind='INVITE'",
        System.currentTimeMillis(),
        id);
    audit.record(Db.str(a, "id"), "INVITE_REVOKED", id, Map.of());
    return Map.of("message", "Einladung widerrufen.");
  }

  private Map<String, Object> validToken(String raw, String kind) {
    if (raw == null || raw.length() > 100) throw Rules.error(400, "Link ungültig oder abgelaufen.");
    var t =
        db.one(
            "SELECT * FROM account_tokens WHERE token_hash=? AND kind=? AND used_at IS NULL AND"
                + " revoked_at IS NULL AND expires_at>?",
            Security.hash(raw),
            kind,
            System.currentTimeMillis());
    if (t == null) throw Rules.error(400, "Link ungültig oder abgelaufen.");
    return t;
  }

  @POST
  @Path("/accept")
  @Transactional
  public Object accept(Accept b) {
    security.limit("accept:global", 100);
    db.lock();
    var t = validToken(b.token(), "INVITE");
    if (db.one("SELECT id FROM members WHERE email=?", t.get("email")) != null)
      throw Rules.error(409, "Konto existiert bereits.");
    String id = Db.id();
    String schoolCode = availableSchoolCode(b.schoolCode(), null);
    db.update(
        "INSERT INTO members(id,email,display_name,school_code,password_hash,role)"
            + " VALUES(?,?,?,?,?,'MEMBER')",
        id,
        t.get("email"),
        Rules.text(b.displayName(), 2, 80, "Anzeigename"),
        schoolCode,
        Security.passwordHash(b.password()));
    db.update(
        "UPDATE account_tokens SET used_at=? WHERE id=?", System.currentTimeMillis(), t.get("id"));
    audit.record(id, "INVITE_ACCEPTED", id, Map.of());
    return Map.of("message", "Konto erstellt. Bitte anmelden.");
  }

  @POST
  @Path("/reset-request")
  @Transactional
  public Object resetRequest(Email b) {
    String email = Rules.email(b.email());
    security.limit("reset:global", 50);
    security.limit("reset:" + email, 5);
    db.lock();
    // Require transport for all requests so the response never reveals account existence.
    if (!mailEnabled) throw Rules.error(503, "E-Mail-Versand ist noch nicht konfiguriert.");
    var u = db.one("SELECT id FROM members WHERE email=? AND active=1", email);
    if (u != null) {
      try {
        issue(email, "RESET", null, null);
      } catch (RuntimeException e) {
        transaction.setRollbackOnly();
        org.jboss.logging.Logger.getLogger(AuthResource.class)
            .warn("Reset-Versand fehlgeschlagen; keine Kontodaten protokolliert.");
      }
    }
    return Map.of("message", NEUTRAL);
  }

  @POST
  @Path("/reset")
  @Transactional
  public Object reset(Reset b) {
    security.limit("reset-redeem:global", 100);
    db.lock();
    var t = validToken(b.token(), "RESET");
    var u = db.one("SELECT id FROM members WHERE email=? AND active=1", t.get("email"));
    if (u == null) throw Rules.error(400, "Link ungültig oder abgelaufen.");
    db.update(
        "UPDATE members SET password_hash=?,version=version+1 WHERE id=?",
        Security.passwordHash(b.password()),
        u.get("id"));
    db.update(
        "UPDATE account_tokens SET used_at=? WHERE id=?", System.currentTimeMillis(), t.get("id"));
    db.update("DELETE FROM sessions WHERE user_id=?", u.get("id"));
    audit.record(Db.str(u, "id"), "PASSWORD_RESET", Db.str(u, "id"), Map.of());
    return Map.of("message", "Passwort zurückgesetzt. Bitte anmelden.");
  }
}
