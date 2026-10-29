package at.ballzeit;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.Map;
import org.jboss.logging.Logger;

@Provider
public class Errors implements ExceptionMapper<Exception> {
  private static final Logger LOG = Logger.getLogger(Errors.class);

  public Response toResponse(Exception e) {
    if (e instanceof WebApplicationException w) {
      if (w.getResponse().hasEntity()) return w.getResponse();
      return Response.status(w.getResponse().getStatus())
          .entity(Map.of("error", "Anfrage konnte nicht verarbeitet werden."))
          .build();
    }
    // No request bodies, credentials or SQL parameter values are logged.
    LOG.error("Interner Fehler vom Typ " + e.getClass().getSimpleName());
    return Response.serverError()
        .entity(Map.of("error", "Interner Fehler. Bitte später erneut versuchen."))
        .build();
  }
}
