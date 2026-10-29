package at.ballzeit;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Path("/api/time")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TimeResource {
  @Inject Db db;
  @Inject Security security;
  @Inject Audit audit;
  @Inject ProjectResource project;

  public record Entry(
      String teamId,
      String activityId,
      String description,
      String start,
      String end,
      String startOffset,
      String endOffset,
      String requestKey,
      Long version,
      String workLabel) {}

  public record Decision(
      List<String> ids, Map<String, Long> versions, String status, String reason) {}

  static final String SELECT =
      "SELECT e.*, (e.ended_at-e.started_at) duration_millis, m.display_name, t.name team_name,"
          + " a.name activity_name,a.color, d.display_name reviewer_name FROM time_entries e JOIN"
          + " members m ON m.id=e.user_id LEFT JOIN teams t ON t.id=e.team_id JOIN activities a ON"
          + " a.id=e.activity_id LEFT JOIN members d ON d.id=e.decided_by";

  private Map<String, Object> entry(String id) {
    var e = db.one("SELECT * FROM time_entries WHERE id=?", id);
    if (e == null) throw Rules.error(404, "Eintrag nicht gefunden.");
    return e;
  }

  private void ownOrAdmin(Map<String, Object> a, Map<String, Object> e) {
    if (!Objects.equals(a.get("id"), e.get("user_id")) && !Security.admin(a))
      throw Rules.error(403, "Du darfst nur eigene Einträge ändern.");
  }

  private Entry prepare(Entry b, Map<String, Object> actor, Map<String, Object> old) {
    String team = old == null ? Db.str(actor, "team_id") : Db.str(old, "team_id");
    if (b.teamId() != null && !Objects.equals(b.teamId(), team))
      throw Rules.error(
          403, "Das Team wird vom Admin zugewiesen und kann im Zeiteintrag nicht geändert werden.");
    if (old == null && project.current().teams() && team == null)
      throw Rules.error(
          409, "Dir ist noch kein Team zugewiesen. Bitte einen Admin oder Owner um die Zuweisung.");
    if (old == null
        && team != null
        && db.one("SELECT id FROM teams WHERE id=? AND active=1", team) == null)
      throw Rules.error(400, "Dein Team ist archiviert. Bitte einen Admin um ein aktives Team.");
    String activity = b.activityId();
    if (b.workLabel() != null) {
      String name = Rules.text(b.workLabel(), 2, 100, "Tätigkeit / Projekt");
      var found =
          db.one(
              "SELECT id,active FROM activities WHERE LOWER(name)=LOWER(?) ORDER BY active DESC,id",
              name);
      if (found != null) {
        activity = Db.str(found, "id");
        if (Db.num(found, "active") == 0
            && (old == null || !Objects.equals(activity, old.get("activity_id"))))
          throw Rules.error(
              400,
              "Diese Tätigkeit ist archiviert. Bitte eine aktive oder neue Tätigkeit eingeben.");
      } else {
        activity = Db.id();
        String[] colors = {"#9562df", "#db7aa5", "#5f99d4", "#4b9b83", "#d3a454", "#9784c4"};
        int index =
            (int)
                (Db.num(db.one("SELECT COUNT(*) total FROM activities"), "total") % colors.length);
        db.update(
            "INSERT INTO activities(id,name,color) VALUES(?,?,?)", activity, name, colors[index]);
        audit.record(Db.str(actor, "id"), "ACTIVITY_DISCOVERED", activity, Map.of("name", name));
      }
    }
    if (db.one("SELECT id FROM activities WHERE id=? AND active=1", activity) == null
        && (old == null || !Objects.equals(activity, old.get("activity_id"))))
      throw Rules.error(400, "Bitte eingeben, woran du arbeitest.");
    Rules.text(b.description(), 3, 1000, "Beschreibung");
    return new Entry(
        team,
        activity,
        b.description(),
        b.start(),
        b.end(),
        b.startOffset(),
        b.endOffset(),
        b.requestKey(),
        b.version(),
        b.workLabel());
  }

  private int approval(Map<String, Object> old) {
    return old == null
        ? project.approvalRequired()
        : Math.max((int) Db.num(old, "approval_required"), project.approvalRequired());
  }

  private String completedStatus(int required) {
    return required == 1 ? "PENDING" : "APPROVED";
  }

  private void noOverlap(String user, long start, long end, String except) {
    if (db.one(
            "SELECT id FROM time_entries WHERE user_id=? AND id<>? AND started_at<? AND (ended_at"
                + " IS NULL OR ended_at>?)",
            user,
            except == null || except.isBlank() ? "-" : except,
            end,
            start)
        != null)
      throw Rules.error(
          409, "Diese Zeit überschneidet sich mit einem anderen Eintrag oder laufenden Timer.");
  }

  @GET
  @Path("/timer")
  public Object timer(@Context ContainerRequestContext r) {
    var a = security.actor(r);
    var e = db.one(SELECT + " WHERE e.user_id=? AND e.status='RUNNING'", a.get("id"));
    return Map.of("serverNow", System.currentTimeMillis(), "timer", e == null ? Map.of() : e);
  }

  @POST
  @Path("/timer")
  @Transactional
  public Object start(Entry b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    String key = Rules.uuid(b.requestKey());
    var old =
        db.one("SELECT * FROM time_entries WHERE user_id=? AND request_key=?", a.get("id"), key);
    if (old != null) return old;
    b = prepare(b, a, null);
    if (db.one("SELECT id FROM time_entries WHERE user_id=? AND status='RUNNING'", a.get("id"))
        != null) throw Rules.error(409, "Es läuft bereits ein Timer.");
    long now = System.currentTimeMillis();
    noOverlap(Db.str(a, "id"), now, Long.MAX_VALUE, "");
    String id = Db.id();
    db.update(
        "INSERT INTO"
            + " time_entries(id,user_id,team_id,activity_id,description,started_at,status,request_key,approval_required)"
            + " VALUES(?,?,?,?,?,?,'RUNNING',?,?)",
        id,
        a.get("id"),
        b.teamId(),
        b.activityId(),
        b.description().strip(),
        now,
        key,
        approval(null));
    audit.record(Db.str(a, "id"), "TIMER_STARTED", id, entry(id));
    return entry(id);
  }

  @POST
  @Path("/timer/{id}/stop")
  @Transactional
  public Object stop(@PathParam("id") String id, Entry b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    var old = entry(id);
    if (!Objects.equals(a.get("id"), old.get("user_id")))
      throw Rules.error(403, "Nur den eigenen Timer stoppen.");
    if (!"RUNNING".equals(old.get("status"))) return old;
    Rules.version(old, b.version());
    b = prepare(b, a, old);
    long now = System.currentTimeMillis();
    Rules.interval(Db.num(old, "started_at"), now, now);
    noOverlap(Db.str(a, "id"), Db.num(old, "started_at"), now, id);
    db.update(
        "UPDATE time_entries SET"
            + " team_id=?,activity_id=?,description=?,ended_at=?,status=?,approval_required=?,version=version+1"
            + " WHERE id=?",
        b.teamId(),
        b.activityId(),
        b.description().strip(),
        now,
        completedStatus(approval(old)),
        approval(old),
        id);
    audit.record(Db.str(a, "id"), "TIMER_STOPPED", id, Map.of("before", old, "after", entry(id)));
    return entry(id);
  }

  @POST
  @Path("/entries")
  @Transactional
  public Object create(Entry b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    String key = Rules.uuid(b.requestKey());
    var duplicate =
        db.one("SELECT * FROM time_entries WHERE user_id=? AND request_key=?", a.get("id"), key);
    if (duplicate != null) return duplicate;
    b = prepare(b, a, null);
    long start = Rules.localTime(b.start(), b.startOffset()),
        end = Rules.localTime(b.end(), b.endOffset());
    Rules.interval(start, end, System.currentTimeMillis());
    noOverlap(Db.str(a, "id"), start, end, "");
    String id = Db.id();
    db.update(
        "INSERT INTO"
            + " time_entries(id,user_id,team_id,activity_id,description,started_at,ended_at,status,request_key,approval_required)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        a.get("id"),
        b.teamId(),
        b.activityId(),
        b.description().strip(),
        start,
        end,
        completedStatus(approval(null)),
        key,
        approval(null));
    audit.record(Db.str(a, "id"), "ENTRY_CREATED", id, entry(id));
    return entry(id);
  }

  @PUT
  @Path("/entries/{id}")
  @Transactional
  public Object edit(@PathParam("id") String id, Entry b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.actor(r);
    var old = entry(id);
    ownOrAdmin(a, old);
    Rules.version(old, b.version());
    b = prepare(b, a, old);
    if ("RUNNING".equals(old.get("status"))) {
      db.update(
          "UPDATE time_entries SET team_id=?,activity_id=?,description=?,version=version+1 WHERE"
              + " id=?",
          b.teamId(),
          b.activityId(),
          b.description().strip(),
          id);
    } else {
      long start = Rules.localTime(b.start(), b.startOffset()),
          end = Rules.localTime(b.end(), b.endOffset());
      Rules.interval(start, end, System.currentTimeMillis());
      noOverlap(Db.str(old, "user_id"), start, end, id);
      db.update(
          "UPDATE time_entries SET"
              + " team_id=?,activity_id=?,description=?,started_at=?,ended_at=?,status=?,approval_required=?,decided_by=NULL,decided_at=NULL,reason=NULL,version=version+1"
              + " WHERE id=?",
          b.teamId(),
          b.activityId(),
          b.description().strip(),
          start,
          end,
          completedStatus(approval(old)),
          approval(old),
          id);
    }
    audit.record(Db.str(a, "id"), "ENTRY_EDITED", id, Map.of("before", old, "after", entry(id)));
    return entry(id);
  }

  @GET
  @Path("/mine")
  public Object mine(@Context ContainerRequestContext r) {
    var a = security.actor(r);
    return db.list(SELECT + " WHERE e.user_id=? ORDER BY e.started_at DESC", a.get("id"));
  }

  @POST
  @Path("/decisions")
  @Transactional
  public Object decide(Decision b, @Context ContainerRequestContext r) {
    db.lock();
    var a = security.admin(r);
    if (b.ids() == null || b.ids().isEmpty() || b.ids().size() > 100 || b.versions() == null)
      throw Rules.error(400, "Einträge ausdrücklich auswählen (maximal 100).");
    if (!Set.of("APPROVED", "DENIED").contains(b.status()))
      throw Rules.error(400, "Ungültige Entscheidung.");
    String reason =
        "DENIED".equals(b.status())
            ? Rules.text(b.reason(), 3, 1000, "Ablehnungsbegründung")
            : null;
    for (String id : new LinkedHashSet<>(b.ids())) {
      var old = entry(id);
      Rules.version(old, b.versions().get(id));
      if (!"PENDING".equals(old.get("status")))
        throw Rules.error(409, "Nur wartende abgeschlossene Einträge können geprüft werden.");
      db.update(
          "UPDATE time_entries SET status=?,decided_by=?,decided_at=?,reason=?,version=version+1"
              + " WHERE id=?",
          b.status(),
          a.get("id"),
          System.currentTimeMillis(),
          reason,
          id);
      audit.record(
          Db.str(a, "id"), "ENTRY_REVIEWED", id, Map.of("before", old, "after", entry(id)));
    }
    return Map.of("message", "Entscheidung gespeichert.");
  }

  public record Filters(
      String from, String to, List<String> people, String team, String activity, String status) {}

  List<Map<String, Object>> filtered(Filters f) {
    String status = f.status() == null || f.status().isBlank() ? "APPROVED" : f.status();
    if (!Set.of("APPROVED", "PENDING", "DENIED", "RUNNING", "ALL").contains(status))
      throw Rules.error(400, "Ungültiger Statusfilter.");
    var sql = new StringBuilder(SELECT + " WHERE 1=1");
    var args = new ArrayList<Object>();
    if (!"ALL".equals(status)) {
      sql.append(" AND e.status=?");
      args.add(status);
    }
    try {
      if (f.from() != null && !f.from().isBlank()) {
        sql.append(" AND e.started_at>=?");
        args.add(
            LocalDate.parse(f.from())
                .atStartOfDay(ZoneId.of("Europe/Vienna"))
                .toInstant()
                .toEpochMilli());
      }
      if (f.to() != null && !f.to().isBlank()) {
        sql.append(" AND e.started_at<?");
        args.add(
            LocalDate.parse(f.to())
                .plusDays(1)
                .atStartOfDay(ZoneId.of("Europe/Vienna"))
                .toInstant()
                .toEpochMilli());
      }
      if (f.from() != null
          && f.to() != null
          && !f.from().isBlank()
          && !f.to().isBlank()
          && LocalDate.parse(f.from()).isAfter(LocalDate.parse(f.to())))
        throw Rules.error(400, "Zeitraum ist ungültig.");
    } catch (java.time.format.DateTimeParseException e) {
      throw Rules.error(400, "Ungültiges Filterdatum.");
    }
    if (f.people() != null && !f.people().isEmpty()) {
      if (f.people().size() > 100) throw Rules.error(400, "Zu viele Personen.");
      sql.append(
          " AND e.user_id IN ("
              + String.join(",", Collections.nCopies(f.people().size(), "?"))
              + ")");
      args.addAll(f.people());
    }
    if (f.team() != null && !f.team().isBlank()) {
      sql.append(" AND e.team_id=?");
      args.add(f.team());
    }
    if (f.activity() != null && !f.activity().isBlank()) {
      sql.append(" AND e.activity_id=?");
      args.add(f.activity());
    }
    return db.list(sql + " ORDER BY e.started_at DESC", args.toArray());
  }

  static long duration(Map<String, Object> e) {
    return e.get("ended_at") == null ? 0 : Db.num(e, "ended_at") - Db.num(e, "started_at");
  }

  static List<Map<String, Object>> breakdown(
      List<Map<String, Object>> rows, String key, String label) {
    Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    for (var row : rows) {
      String id = Db.str(row, key);
      if (id == null) id = "unassigned";
      var g =
          groups.computeIfAbsent(
              id,
              k -> {
                var v = new LinkedHashMap<String, Object>();
                v.put("id", k);
                v.put("name", row.get(label) == null ? "Ohne Team" : row.get(label));
                v.put("color", key.equals("activity_id") ? row.get("color") : null);
                v.put("durationMillis", 0L);
                return v;
              });
      g.put("durationMillis", ((Number) g.get("durationMillis")).longValue() + duration(row));
    }
    return new ArrayList<>(groups.values());
  }

  @GET
  @Path("/overview")
  public Object overview(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("person") List<String> people,
      @QueryParam("team") String team,
      @QueryParam("activity") String activity,
      @QueryParam("status") String status,
      @Context ContainerRequestContext r) {
    security.actor(r);
    var f = new Filters(from, to, people, team, activity, status);
    var rows = filtered(f);
    return statistics(rows, status == null || status.isBlank() ? "APPROVED" : status);
  }

  static Map<String, Object> statistics(List<Map<String, Object>> rows, String status) {
    var result = new LinkedHashMap<String, Object>();
    result.put("entries", rows);
    result.put("status", status);
    long total = rows.stream().mapToLong(TimeResource::duration).sum();
    long count = rows.stream().filter(e -> e.get("ended_at") != null).count();
    result.put("durationMillis", total);
    result.put("completedCount", count);
    result.put("averageMillis", count == 0 ? 0 : (double) total / count);
    result.put("activePeople", rows.stream().map(e -> e.get("user_id")).distinct().count());
    result.put("people", breakdown(rows, "user_id", "display_name"));
    result.put("activities", breakdown(rows, "activity_id", "activity_name"));
    result.put("teams", breakdown(rows, "team_id", "team_name"));
    var byStatus = new ArrayList<Map<String, Object>>();
    for (String code : List.of("APPROVED", "PENDING", "DENIED", "RUNNING")) {
      var group = rows.stream().filter(e -> code.equals(e.get("status"))).toList();
      byStatus.add(
          Map.of(
              "id",
              code,
              "name",
              code,
              "durationMillis",
              group.stream().mapToLong(TimeResource::duration).sum(),
              "count",
              group.size()));
    }
    result.put("statuses", byStatus);
    var days = new TreeMap<String, Long>();
    var weekdays = new long[7];
    for (var row : rows) {
      if (row.get("ended_at") == null) continue;
      var d =
          Instant.ofEpochMilli(Db.num(row, "started_at"))
              .atZone(ZoneId.of("Europe/Vienna"))
              .toLocalDate();
      days.merge(d.toString(), duration(row), Long::sum);
      weekdays[d.getDayOfWeek().getValue() - 1] += duration(row);
    }
    result.put(
        "daily",
        days.entrySet().stream()
            .map(e -> Map.of("name", e.getKey(), "durationMillis", e.getValue()))
            .toList());
    var weekdaysResult = new ArrayList<Map<String, Object>>();
    String[] names = {
      "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"
    };
    for (int i = 0; i < 7; i++)
      weekdaysResult.add(Map.of("name", names[i], "durationMillis", weekdays[i]));
    result.put("weekdays", weekdaysResult);
    return result;
  }

  @GET
  @Path("/export")
  @Produces("text/csv; charset=UTF-8")
  public Response export(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("person") List<String> people,
      @QueryParam("team") String team,
      @QueryParam("activity") String activity,
      @QueryParam("status") String status,
      @Context ContainerRequestContext r) {
    security.actor(r);
    var rows = filtered(new Filters(from, to, people, team, activity, status));
    var s =
        new StringBuilder(
            "\uFEFFPerson;Team bei Erfassung;Tätigkeit / Projekt;Beschreibung;Beginn"
                + " (Europe/Vienna);Ende (Europe/Vienna);Dauer"
                + " (Sekunden);Freigabepflicht;Status;Geprüft von;Entscheidung;Begründung\r\n");
    var fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Europe/Vienna"));
    for (var e : rows) {
      Object[] cells = {
        e.get("display_name"),
        e.get("team_name"),
        e.get("activity_name"),
        e.get("description"),
        fmt.format(Instant.ofEpochMilli(Db.num(e, "started_at"))),
        e.get("ended_at") == null ? "" : fmt.format(Instant.ofEpochMilli(Db.num(e, "ended_at"))),
        java.math.BigDecimal.valueOf(duration(e), 3).toPlainString(),
        Db.num(e, "approval_required") == 1 ? "ja" : "nein",
        e.get("status"),
        e.get("reviewer_name"),
        e.get("decided_at") == null
            ? ""
            : fmt.format(Instant.ofEpochMilli(Db.num(e, "decided_at"))),
        e.get("reason")
      };
      s.append(String.join(";", Arrays.stream(cells).map(Rules::csv).toList())).append("\r\n");
    }
    return Response.ok(s.toString())
        .header("Content-Disposition", "attachment; filename=time-tracker.csv")
        .header("Cache-Control", "no-store")
        .build();
  }

  @GET
  @Path("/entries/{id}/history")
  public Object history(@PathParam("id") String id, @Context ContainerRequestContext r) {
    security.admin(r);
    entry(id);
    return db.list(
        "SELECT a.*,m.display_name actor_name FROM audit_events a LEFT JOIN members m ON"
            + " m.id=a.actor_id WHERE a.entity_id=? ORDER BY a.created_at DESC",
        id);
  }
}
