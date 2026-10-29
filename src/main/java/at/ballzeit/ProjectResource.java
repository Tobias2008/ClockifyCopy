package at.ballzeit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.util.*;

/** One shared project; presets configure real workflow features, personal themes stay per user. */
@Path("/api/project")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectResource {
  @Inject Db db;
  @Inject Security security;
  @Inject Audit audit;

  public record Settings(
      String projectName, String preset, Integer weeklyGoalMinutes, Long version) {}

  public record Preset(
      String id, String name, String description, boolean teams, boolean reviews, boolean goals) {}

  public static final List<Preset> PRESETS =
      List.of(
          new Preset(
              "MATURABALL",
              "Maturaball",
              "Teams koordinieren, gemeinsam vorbereiten und Zeiten gegenseitig freigeben.",
              true,
              true,
              false),
          new Preset(
              "JAHRESPROJEKT",
              "Jahresprojekt",
              "Ein Schuljahr im Blick: Teamarbeit, Freigaben und ein persönliches Wochenziel.",
              true,
              true,
              true),
          new Preset(
              "VEREIN",
              "Vereinsarbeit",
              "Ehrenamt unkompliziert erfassen: feste Teams und direkt gültige Zeiten ohne"
                  + " Prüfungsrunde.",
              true,
              false,
              false),
          new Preset(
              "FREIES_PROJEKT",
              "Freies Projekt",
              "Direkt loslegen: Tätigkeiten, Statistiken und Wochenziele ohne Team- oder"
                  + " Freigabepflicht.",
              false,
              false,
              true));

  public static Preset preset(String id) {
    return PRESETS.stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> Rules.error(400, "Ungültige Projektvorlage."));
  }

  public Map<String, Object> settings() {
    var data = db.one("SELECT * FROM project_settings WHERE id=1");
    var result = new LinkedHashMap<String, Object>(data);
    result.put("features", preset(Db.str(data, "preset")));
    result.put("presets", PRESETS);
    result.put(
        "pendingReviews",
        Db.num(
            db.one(
                "SELECT COUNT(*) total FROM time_entries WHERE approval_required=1 AND"
                    + " status='PENDING'"),
            "total"));
    return result;
  }

  public Preset current() {
    return preset(Db.str(db.one("SELECT preset FROM project_settings WHERE id=1"), "preset"));
  }

  public int approvalRequired() {
    return current().reviews() ? 1 : 0;
  }

  @GET
  public Object get(@Context ContainerRequestContext r) {
    security.actor(r);
    return settings();
  }

  @GET
  @Path("/presets")
  public Object presets() {
    return PRESETS;
  }

  @PUT
  @Transactional
  public Object update(Settings b, @Context ContainerRequestContext r) {
    db.lock();
    var actor = security.admin(r);
    if (!"OWNER".equals(actor.get("role")))
      throw Rules.error(403, "Nur Owner dürfen die Projektvorlage ändern.");
    var old = db.one("SELECT * FROM project_settings WHERE id=1");
    Rules.version(old, b.version());
    save(b.projectName(), b.preset(), b.weeklyGoalMinutes());
    audit.record(
        Db.str(actor, "id"),
        "PROJECT_UPDATED",
        "00000000-0000-0000-0000-000000000001",
        Map.of("before", old, "after", db.one("SELECT * FROM project_settings WHERE id=1")));
    return settings();
  }

  public void save(String name, String preset, Integer goal) {
    preset(preset);
    if (goal == null || goal < 0 || goal > 6000)
      throw Rules.error(400, "Wochenziel: 0 bis 100 Stunden.");
    db.update(
        "UPDATE project_settings SET"
            + " project_name=?,preset=?,weekly_goal_minutes=?,version=version+1 WHERE id=1",
        Rules.text(name, 2, 100, "Projektname"),
        preset,
        goal);
  }
}
