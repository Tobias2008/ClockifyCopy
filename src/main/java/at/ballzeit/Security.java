package at.ballzeit;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class Security {
  @Inject Db db;

  @ConfigProperty(name = "ballzeit.cookie-secure")
  boolean secure;

  private static final SecureRandom RANDOM = new SecureRandom();

  public static String token() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String passwordHash(String s) {
    return BcryptUtil.bcryptHash(Rules.password(s), 12);
  }

  public static boolean matches(String s, String hash) {
    return s != null
        && s.getBytes(StandardCharsets.UTF_8).length <= 72
        && BcryptUtil.matches(s, hash);
  }

  public Map<String, Object> authenticate(String token) {
    return token == null
        ? null
        : db.one(
            "SELECT"
                + " m.id,m.display_name,m.school_code,m.role,m.team_id,m.color_preset,m.color_mode,m.version,s.csrf"
                + " FROM sessions s JOIN members m ON m.id=s.user_id WHERE s.token_hash=? AND"
                + " s.expires_at>? AND m.active=1",
            hash(token),
            System.currentTimeMillis());
  }

  public Map<String, Object> actor(ContainerRequestContext request) {
    var initial = (Map<?, ?>) request.getProperty("actor");
    if (initial == null) throw Rules.error(401, "Bitte anmelden.");
    var cookie = request.getCookies().get("ballzeit_session");
    var user = authenticate(cookie == null ? null : cookie.getValue());
    if (user == null) throw Rules.error(401, "Sitzung nicht mehr gültig.");
    return user;
  }

  public static boolean admin(Map<String, Object> a) {
    return Set.of("ADMIN", "OWNER").contains(Db.str(a, "role"));
  }

  public Map<String, Object> admin(ContainerRequestContext r) {
    var a = actor(r);
    if (!admin(a)) throw Rules.error(403, "Verwaltungsrechte erforderlich.");
    return a;
  }

  public String cookie(String token) {
    return "ballzeit_session="
        + token
        + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
        + (token.isEmpty() ? 0 : 43200)
        + (secure ? "; Secure" : "");
  }

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void limit(String key, int max) {
    db.lock();
    long now = System.currentTimeMillis();
    String bucket = hash(key);
    var row = db.one("SELECT * FROM rate_limits WHERE bucket=?", bucket);
    if (row == null) {
      db.update("INSERT INTO rate_limits(bucket,window_start,attempts) VALUES(?,?,1)", bucket, now);
      return;
    }
    if (now - Db.num(row, "window_start") > 900000) {
      db.update("UPDATE rate_limits SET attempts=1,window_start=? WHERE bucket=?", now, bucket);
      return;
    }
    if (Db.num(row, "attempts") >= max)
      throw Rules.error(429, "Zu viele Versuche. Bitte nach 15 Minuten erneut versuchen.");
    db.update("UPDATE rate_limits SET attempts=attempts+1 WHERE bucket=?", bucket);
  }
}

@Provider
class SessionFilter implements ContainerRequestFilter {
  @Inject Security security;

  @Override
  public void filter(ContainerRequestContext r) {
    if (!r.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/")) return;
    var cookie = r.getCookies().get("ballzeit_session");
    var actor = security.authenticate(cookie == null ? null : cookie.getValue());
    r.setProperty("actor", actor);
    String path = r.getUriInfo().getPath().replaceFirst("^/", "");
    boolean publicPath =
        Set.of(
                "api/auth/login",
                "api/auth/reset-request",
                "api/auth/reset",
                "api/auth/accept",
                "api/auth/bootstrap",
                "api/project/presets")
            .contains(path);
    if (!publicPath && actor == null) {
      r.abortWith(Response.status(401).entity(Map.of("error", "Bitte anmelden.")).build());
      return;
    }
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod())) {
      String origin = r.getHeaderString("Origin");
      // Every browser mutation is same-origin; curl/CLI requires the same configured Origin.
      if (origin == null || !origin.equals(PublicOrigin.value(r))) {
        r.abortWith(
            Response.status(403).entity(Map.of("error", "Ungültige Anfrageherkunft.")).build());
        return;
      }
      if (!publicPath
          && (actor == null
              || !Objects.equals(actor.get("csrf"), r.getHeaderString("X-CSRF-Token"))))
        r.abortWith(
            Response.status(403)
                .entity(Map.of("error", "Sicherheitsprüfung fehlgeschlagen. Bitte neu laden."))
                .build());
    }
  }
}

@ApplicationScoped
class PublicOrigin {
  static String value(ContainerRequestContext r) {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getValue("ballzeit.public-url", String.class)
        .replaceAll("/$", "");
  }
}

@Provider
class SecurityHeaders implements ContainerResponseFilter {
  public void filter(ContainerRequestContext r, ContainerResponseContext s) {
    s.getHeaders()
        .putSingle(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action"
                + " 'self'");
    s.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
    s.getHeaders().putSingle("Referrer-Policy", "no-referrer");
    s.getHeaders().putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    if (r.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/"))
      s.getHeaders().putSingle("Cache-Control", "no-store");
  }
}
