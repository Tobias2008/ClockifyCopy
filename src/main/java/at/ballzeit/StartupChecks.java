package at.ballzeit;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URI;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class StartupChecks {
  void validate(@Observes StartupEvent event) {
    var c = ConfigProvider.getConfig();
    var url = URI.create(c.getValue("ballzeit.public-url", String.class));
    if (url.getHost() == null
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null
        || (url.getPath() != null && !url.getPath().isEmpty() && !url.getPath().equals("/")))
      throw new IllegalStateException("PUBLIC_URL muss eine reine Origin sein.");
    if (LaunchMode.current() == LaunchMode.NORMAL) {
      if (!"https".equals(url.getScheme()) || !c.getValue("ballzeit.cookie-secure", Boolean.class))
        throw new IllegalStateException(
            "Produktion benötigt HTTPS PUBLIC_URL und sichere Cookies.");
      if (c.getValue("ballzeit.mail-enabled", Boolean.class)
          && (c.getOptionalValue("SMTP_HOST", String.class).orElse("").isBlank()
              || c.getOptionalValue("quarkus.mailer.from", String.class).orElse("").isBlank()
              || c.getValue("quarkus.mailer.mock", Boolean.class)))
        throw new IllegalStateException(
            "Produktionsversand benötigt SMTP_HOST, SMTP_FROM und deaktivierten Mock-Modus.");
    }
  }
}
