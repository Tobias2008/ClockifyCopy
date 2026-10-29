package at.ballzeit;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {
  @Inject Db db;
  @Inject Security security;
  @Inject Audit audit;
  @Inject AuthResource accounts;
  @Inject ProjectResource project;

  public record Catalog(String name, String color, Boolean active, Long version) {}

  public record Member(
      String displayName,
      String schoolCode,
      String teamId,
      String role,
      Boolean active,
      Long version) {}

  @GET
  @Path("/catalog")
  public Object catalog(@Context ContainerRequestContext r) {
    security.actor(r);
    return Map.of(
        "teams",
        db.list("SELECT * FROM teams ORDER BY name"),
        "activities",
        db.list("SELECT * FROM activities ORDER BY name"),
        "people",
        db.list("SELECT id,display_name FROM members ORDER BY display_name"),
        "project",
        project.settings());
  }

  private String table(String kind) {
    return switch (kind) {
      case "teams" -> "teams";
      case "activities" -> "activities";
      default -> throw Rules.error(404, "Nicht gefunden.");
    };
  }

  private String color(String value) {
    if (value == null || !value.matches("#[a-fA-F0-9]{6}"))
      throw Rules.error(400, "Farbe als #RRGGBB angeben.");
    return value;
  }

  @POST
  @Path("/admin/{kind:teams|activities}")
  @Transactional
  public Object create(
      @PathParam("kind") String kind, Catalog b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.admin(r);
    String t = table(kind), id = Db.id(), name = Rules.text(b.name(), 2, 100, "Name");
    if (t.equals("teams")) db.update("INSERT INTO teams(id,name) VALUES(?,?)", id, name);
    else
      db.update("INSERT INTO activities(id,name,color) VALUES(?,?,?)", id, name, color(b.color()));
    audit.record(Db.str(a, "id"), "CATALOG_CREATED", id, Map.of("kind", kind, "name", name));
    return Map.of("id", id);
  }

  @PUT
  @Path("/admin/{kind:teams|activities}/{id}")
  @Transactional
  public Object update(
      @PathParam("kind") String kind,
      @PathParam("id") String id,
      Catalog b,
      @Context ContainerRequestContext r) {
    db.lock();
    var a = security.admin(r);
    String t = table(kind);
    var old = db.one("SELECT * FROM " + t + " WHERE id=?", id);
    if (old == null) throw Rules.error(404, "Nicht gefunden.");
    Rules.version(old, b.version());
    String name = Rules.text(b.name(), 2, 100, "Name");
    if (b.active() == null) throw Rules.error(400, "Aktiv-Status fehlt.");
    // Teams with members may be archived; invitations and new assignments must choose active teams.
    if (t.equals("teams"))
      db.update(
          "UPDATE teams SET name=?,active=?,version=version+1 WHERE id=?",
          name,
          b.active() ? 1 : 0,
          id);
    else
      db.update(
          "UPDATE activities SET name=?,color=?,active=?,version=version+1 WHERE id=?",
          name,
          color(b.color()),
          b.active() ? 1 : 0,
          id);
    audit.record(
        Db.str(a, "id"),
        "CATALOG_UPDATED",
        id,
        Map.of("before", old, "after", db.one("SELECT * FROM " + t + " WHERE id=?", id)));
    return Map.of("message", "Gespeichert.");
  }

  @GET
  @Path("/admin/members")
  public Object members(@Context ContainerRequestContext r) {
    security.admin(r);
    return db.list(
        "SELECT id,email,display_name,school_code,role,team_id,active,version FROM members ORDER BY"
            + " display_name");
  }

  @PUT
  @Path("/admin/members/{id}")
  @Transactional
  public Object member(@PathParam("id") String id, Member b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.admin(r);
    var old =
        db.one(
            "SELECT id,email,display_name,school_code,role,team_id,active,version FROM members"
                + " WHERE id=?",
            id);
    if (old == null) throw Rules.error(404, "Mitglied nicht gefunden.");
    Rules.version(old, b.version());
    if (b.active() == null
        || b.role() == null
        || !Set.of("OWNER", "ADMIN", "MEMBER").contains(b.role()))
      throw Rules.error(400, "Rolle oder Aktiv-Status ungültig.");
    if (!"OWNER".equals(a.get("role"))
        && ("OWNER".equals(old.get("role")) || !Objects.equals(b.role(), old.get("role"))))
      throw Rules.error(403, "Nur Owner dürfen Rollen ändern; Owner-Konten sind geschützt.");
    if ("OWNER".equals(old.get("role"))
        && Db.num(old, "active") == 1
        && (!b.active() || !b.role().equals("OWNER"))
        && Db.num(
                db.one("SELECT COUNT(*) total FROM members WHERE role='OWNER' AND active=1"),
                "total")
            <= 1) throw Rules.error(409, "Der letzte aktive Owner muss erhalten bleiben.");
    if (b.teamId() != null
        && !b.teamId().isBlank()
        && !Objects.equals(b.teamId(), old.get("team_id"))
        && db.one("SELECT id FROM teams WHERE id=? AND active=1", b.teamId()) == null)
      throw Rules.error(400, "Aktives Stammteam auswählen.");
    if (!b.active()) {
      var running = db.one("SELECT * FROM time_entries WHERE user_id=? AND status='RUNNING'", id);
      if (running != null) {
        long now = System.currentTimeMillis();
        if (now <= Db.num(running, "started_at"))
          throw Rules.error(409, "Timer gerade gestartet. Bitte erneut versuchen.");
        db.update(
            "UPDATE time_entries SET ended_at=?,status=?,approval_required=?,version=version+1"
                + " WHERE id=?",
            now,
            Math.max((int) Db.num(running, "approval_required"), project.approvalRequired()) == 1
                ? "PENDING"
                : "APPROVED",
            Math.max((int) Db.num(running, "approval_required"), project.approvalRequired()),
            running.get("id"));
        audit.record(
            Db.str(a, "id"),
            "TIMER_STOPPED_ON_DEACTIVATION",
            Db.str(running, "id"),
            Map.of(
                "before",
                running,
                "after",
                db.one("SELECT * FROM time_entries WHERE id=?", running.get("id"))));
      }
    }
    db.update(
        "UPDATE members SET"
            + " display_name=?,school_code=?,team_id=?,role=?,active=?,version=version+1 WHERE"
            + " id=?",
        Rules.text(b.displayName(), 2, 80, "Anzeigename"),
        accounts.availableSchoolCode(
            b.schoolCode() == null ? Db.str(old, "school_code") : b.schoolCode(), id),
        b.teamId(),
        b.role(),
        b.active() ? 1 : 0,
        id);
    if (!b.active() || !Objects.equals(b.role(), old.get("role")))
      db.update("DELETE FROM sessions WHERE user_id=?", id);
    if (!b.active())
      db.update(
          "UPDATE account_tokens SET revoked_at=? WHERE email=? AND used_at IS NULL",
          System.currentTimeMillis(),
          old.get("email"));
    audit.record(
        Db.str(a, "id"),
        "MEMBER_UPDATED",
        id,
        Map.of(
            "before",
            old,
            "after",
            db.one(
                "SELECT id,email,display_name,school_code,role,team_id,active,version FROM members"
                    + " WHERE id=?",
                id)));
    return Map.of("message", "Mitglied gespeichert. Historische Teamzuordnungen bleiben erhalten.");
  }

  @GET
  @Path("/admin/history")
  public Object history(@Context ContainerRequestContext r) {
    security.admin(r);
    return db.list(
        "SELECT a.*,m.display_name actor_name FROM audit_events a LEFT JOIN members m ON"
            + " m.id=a.actor_id ORDER BY a.created_at DESC FETCH FIRST 300 ROWS ONLY");
  }
}
