package at.ballzeit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class Audit {
  @Inject Db db;
  @Inject ObjectMapper json;

  public void record(String actor, String action, String entity, Object details) {
    try {
      db.update(
          "INSERT INTO audit_events(id,actor_id,action,entity_id,details,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          Db.id(),
          actor,
          action,
          entity,
          json.writeValueAsString(details),
          System.currentTimeMillis());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
