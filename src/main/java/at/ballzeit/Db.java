package at.ballzeit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Small parameterized JDBC helper. Connections participate in Quarkus JTA transactions. */
@ApplicationScoped
public class Db {
  @Inject DataSource source;

  public List<Map<String, Object>> list(String sql, Object... args) {
    try (var c = source.getConnection();
        var s = c.prepareStatement(sql)) {
      bind(s, args);
      try (var r = s.executeQuery()) {
        var rows = new ArrayList<Map<String, Object>>();
        while (r.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
            Object v = r.getObject(i);
            if (v instanceof Clob clob) v = clob.getSubString(1, (int) clob.length());
            row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), v);
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Datenbankoperation fehlgeschlagen", e);
    }
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public int update(String sql, Object... args) {
    try (var c = source.getConnection();
        var s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Datenbankoperation fehlgeschlagen", e);
    }
  }

  private void bind(PreparedStatement s, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
  }

  public void lock() {
    one("SELECT id FROM app_lock WHERE id=1 FOR UPDATE");
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String str(Map<String, Object> r, String k) {
    return r.get(k) == null ? null : r.get(k).toString();
  }

  public static long num(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).longValue();
  }
}
