# Time Tracker

Zeiterfassung für Maturaball, Jahresprojekt, Verein oder ein freies Projekt: Java/Quarkus, Oracle und HTML/CSS/JavaScript ohne Frontend-Framework. Die Anwendung enthält keine Standardkonten und legt beim Start keine Beispieldaten an. Frontend und API liegen unter derselben Origin.

## Bereits vorbereitet in diesem Arbeitsverzeichnis

Für diesen Rechner wurde eine lokale Oracle-Free-Instanz im Container `ballzeit-oracle-test` (nur `127.0.0.1:15219`) eingerichtet. Sie läuft aktuell; ihr separates Schema `BALLZEIT_LOCAL` ist für deine eigene Einrichtung leer. Die zufälligen DB-Zugangsdaten und der einmalige Einrichtungsschlüssel stehen in der **git-ignorierten, nur lokal lesbaren `.env`**. Es gibt dort kein vorgegebenes Benutzerkonto oder Passwort. Starte `mvn quarkus:dev`, öffne `http://localhost:8080` und wähle **Erste Einrichtung**. Den `BOOTSTRAP_KEY` aus `.env` verwenden und nach erfolgreicher Einrichtung entfernen. Die QA-Beispieldaten liegen in getrennten Schemas und erscheinen nicht in deiner Anwendung.

Falls der Container gestoppt wurde: `docker start ballzeit-oracle-test`, dann mit `docker exec ballzeit-oracle-test healthcheck.sh` auf Betriebsbereitschaft prüfen. Zum Ressourcensparen später `docker stop ballzeit-oracle-test` verwenden; nicht löschen, solange du seine Daten brauchst. Ein Container-Neustart behält Daten, ein Entfernen dieses temporären Containers ohne Volume würde sie löschen. Für dauerhaften Schulbetrieb eine verwaltete Oracle-PDB oder persistent gesicherte Datenbank bereitstellen. Docker ist nur diese zusätzlich eingerichtete lokale Entwicklungshilfe. Auf einem anderen Rechner gelten die folgenden Installationsschritte.

## Schnellstart

Benötigt werden **JDK 21 oder neuer**, **Maven 3.9.12+** und eine erreichbare **Oracle-Datenbank mit eigener PDB bzw. eigenem Schema**. Geprüfte Versionen und Ergebnisse stehen in [docs/PRUEFUNG.md](docs/PRUEFUNG.md). Docker ist für die Anwendung nicht erforderlich.

1. In Oracle als DBA innerhalb der Anwendungs-PDB [ops/create-schema.sql](ops/create-schema.sql) mit SQLcl/SQL*Plus ausführen. Den Schema-Namen ggf. überall einheitlich ersetzen.
2. `.env.example` nach `.env` kopieren. Datenbank-URL, Benutzer und Passwort setzen. Für lokale Entwicklung `PUBLIC_URL=http://localhost:8080` verwenden. Die `.env` wird von Quarkus gelesen; nicht ins Repository aufnehmen. Restriktive Dateirechte setzen (`chmod 600 .env`).
3. Einmaligen `BOOTSTRAP_KEY` mit mindestens 32 zufälligen Zeichen erzeugen, etwa `openssl rand -hex 32`, und in `.env` eintragen. Keinen bekannten Beispielwert verwenden.
4. Starten:

   ```sh
   mvn quarkus:dev
   ```

5. `http://localhost:8080` öffnen, **Erste Einrichtung** wählen und Owner mit Anzeigename, persönlichem Schulkürzel, eigenem Passwort sowie Einrichtungsschlüssel eingeben. Danach normal anmelden. Nach erfolgreicher Einrichtung `BOOTSTRAP_KEY` aus der Konfiguration entfernen und die Anwendung neu starten. Zusätzlich verhindert ein dauerhaftes Datenbankkennzeichen jede erneute Einrichtung, auch wenn der Schlüssel versehentlich erhalten bleibt.
6. Unter **Teams & Tätigkeiten** Teams erstellen und unter **Mitglieder** zuweisen. Tätigkeiten lassen sich dort verwalten oder bei der Zeiterfassung neu eingeben. Unter **Mitglieder** Einladungen versenden. Im Entwicklungsprofil fängt Quarkus die E-Mails ab und zeigt deren Inhalt im lokalen Terminal; es wird kein echter SMTP-Server benötigt. Einladungslinks funktionieren auf diesem Entwicklungsrechner. Für Tests auf anderen Geräten muss `PUBLIC_URL` genau der verwendeten Origin entsprechen und die Bind-Adresse ausdrücklich angepasst werden.

Das Entwicklungsprofil ist ausschließlich für lokale Arbeit bestimmt. Es aktiviert unsichere HTTP-Cookies für localhost und den Mail-Mock, aber keine Beispieldaten. Die Entwicklungsoberfläche `/q/dev-ui` ist nur im Entwicklungsmodus vorhanden. Entwicklung niemals öffentlich exponieren.

## Bedienung

- Timer: Dein vom Admin zugewiesenes Team wird angezeigt und serverseitig übernommen. Unter **Woran arbeitest du?** eine Tätigkeit auswählen oder neu eingeben, dazu mindestens drei Zeichen Beschreibung erfassen, starten und später stoppen. Während der Laufzeit dürfen die Felder geändert werden; beim Stoppen werden sie endgültig gespeichert. Der Timer bleibt in Oracle und wird nach erneutem Login wiederhergestellt. Die Browseranzeige wird anhand einer Serverzeit synchronisiert.
- **Zeit nachtragen**: Beginn und Ende mit getrenntem Datum wählen. Einträge über Mitternacht sind erlaubt. Nicht existierende Uhrzeiten beim Frühlingswechsel werden abgelehnt. Bei doppelten Herbststunden ist der UTC-Offset ausdrücklich auszuwählen. Bei normalen Zeiten ist „Automatisch“ richtig.
- Bei Vorlagen mit Freigabepflicht sind neue abgeschlossene Zeiten **PENDING**. Ohne Freigabepflicht werden sie direkt als APPROVED mit `approval_required=0` erfasst; es wird kein menschlicher Prüfer erfunden. Admin oder Owner prüft sie unter **Prüfung**, öffnet die Details und wählt Einträge ausdrücklich für eine Entscheidung aus. Ablehnungen brauchen eine Begründung. Admins und Owner dürfen auch eigene Zeiten genehmigen oder ablehnen. Mitglieder dürfen keine Zeiten prüfen. Entscheider, Zeitpunkt und Historie werden auch bei eigenen Freigaben vollständig gespeichert.
- Änderungen an geprüften Zeiten setzen den Status auf PENDING und entfernen die aktuelle Entscheidung. Frühere Inhalte und Entscheidungen bleiben in der Historie.
- **Zeitübersicht** zeigt standardmäßig APPROVED. **Alle Status** zeigt zusätzlich wartende, abgelehnte und laufende Einträge. Dabei werden Statussummen getrennt dargestellt und die Gesamtzeit ausdrücklich als erfasste Zeit einschließlich ungültiger/ungeprüfter Einträge beschriftet. Laufende Timer zählen nicht zur Dauer. Zeitraum (inklusive Enddatum, nach Beginn des Eintrags), Personen, bearbeitetes Team und Aufgabenbereich sind kombinierbar. Filter zuerst anwenden; CSV und Diagramm verwenden danach dieselbe Auswahl. Mehrere Personen im Auswahlmenü per Klick oder Tastatur markieren. Alle Filter gelten gemeinsam als Schnittmenge; innerhalb des Personenfilters genügt eine ausgewählte Person. Diagramme haben stets eine textliche Aufschlüsselung.
- Neue Konten starten ohne Stammteam. Ausschließlich Admin und Owner erstellen Teams und weisen diese anschließend unter **Mitglieder** zu. Ein Schulkürzel (2–32 Zeichen; Buchstaben a–z, Ziffern, Punkt, Bindestrich oder Unterstrich) ist bei jeder Kontoerstellung Pflicht, wird kleingeschrieben und darf nur einmal vorkommen. Verwende ein eigenes Time-Tracker-Passwort, **nicht dein Schulpasswort**. Bei Konten aus Versionen vor Migration V3 muss ein Admin das bislang unbekannte Schulkürzel nachtragen; es wird kein fiktives Kürzel erzeugt.
- Das Team eines neuen Eintrags wird fest aus der aktuellen Admin-Zuweisung übernommen. Ein Mitglied kann diesen Wert nicht durch einen manipulierten Request ändern. Tätigkeiten sind davon unabhängig: Ein Mitglied im Team Kommunikation kann beispielsweise an Social Media arbeiten. Änderungen des Stammteams ändern keine Zeiteinträge; auch ein laufender Timer behält das Team seines Starts. Archivierte Teams und Aufgabenbereiche bleiben historisch erhalten; sie dürfen neuen Zeiten nicht zugeordnet werden. Unveränderte historische Zuordnungen können bei einer Korrektur bestehen bleiben.
- Beim Deaktivieren werden laufende Timer mit der aktuellen Serverzeit als PENDING abgeschlossen und separat in der Historie protokolliert. Sitzungen werden sofort widerrufen.

## Projektvorlagen, Darstellung und Auswertung

Die Anwendung heißt **Time Tracker**. Bei der ersten Einrichtung werden Projektname und eine von vier Vorlagen gewählt. Bestehende Installationen behalten ihre Daten und starten mit der Vorlage Maturaball. Owner können die Vorlage unter **Einstellungen → Projekt & Vorlage** ändern.

| Vorlage | Teamzuweisung nötig | Freigabe für neue Zeiten | Wochenziel |
| --- | --- | --- | --- |
| Maturaball | Ja | Ja | Nein |
| Jahresprojekt | Ja | Ja | Ja |
| Vereinsarbeit | Ja | Nein, direkt erfasst | Nein |
| Freies Projekt | Nein | Nein, direkt erfasst | Ja |

Pro Installation besteht ein gemeinsames Projekt. Die Vorlagen sind keine getrennten Datenbanken oder zusätzlichen Arbeitsbereiche. Ein Wechsel löscht keine Einträge. Bereits freigabepflichtige Einträge bleiben freigabepflichtig; bestehende offene Prüfungen bleiben erreichbar. Das Wochenziel wird pro Person anhand ihrer genehmigten/direkt erfassten Zeit seit dem aktuellen Montag in Europe/Vienna berechnet. Die Höhe bestimmt der Owner.

Unter **Einstellungen → Farben & Darstellung** wählt jede Person Lila/Pink, Grün, Blau oder Rot, jeweils in Hell oder Dunkel. Standard ist Lila/Pink im Hellmodus. Die Wahl wird beim Speichern im eigenen Konto abgelegt; ausschließlich die Darstellungswahl wird zusätzlich im Browser zwischengespeichert, keine Authentifizierungstokens.

Die Kopfzeile sitzt auf allen angemeldeten Seiten oben und bleibt beim Scrollen sichtbar. Einheitliche Auswahlmenüs unterstützen Tastaturbedienung und Mehrfachauswahl per Klick. Leere Datumsfilter zeigen ausdrücklich, dass keine Grenze gesetzt ist. Erst **Filter anwenden** aktualisiert Ergebnisse und Export; eine geänderte, noch nicht angewendete Auswahl wird gekennzeichnet.

Die Zeitübersicht enthält Verlauf, Tätigkeitsanteile, Beiträge nach Personen, Teamstunden und Wochentage sowie Anzahl, Durchschnitt und getrennte Statussummen. Alle Werte stammen aus denselben serverseitig gefilterten Einträgen. Tages- und Wochentagszuordnung beziehen sich auf das Startdatum in Europe/Vienna, auch bei Einträgen über Mitternacht. Jedes Diagramm hat eine textliche Aufschlüsselung. Der CSV-Export enthält zusätzlich die Freigabepflicht.

## Aufbau und Entscheidungen

- `AuthResource`: Anmeldung, Sitzungen, Einladungen, Passwort-Reset und Bootstrap.
- `TimeResource`: Timer, Einträge, Prüfungen, Auswertung und CSV.
- `AdminResource`: Kataloge, Mitglieder und Historie.
- `ProjectResource`: Projektvorlagen, Funktionsumfang und Wochenziel.
- `Db`: kleiner parametrisierter JDBC-Zugriff, eingebunden in Quarkus-JTA-Transaktionen. Keine dynamischen SQL-Werte aus Benutzereingaben; Tabellennamen werden nur aus einer festen Positivliste gewählt.
- `Security`, `Rules`, `Audit`: gemeinsame Authentifizierung, Validierung und Historie.
- `src/main/resources/META-INF/resources`: statische deutsche Oberfläche ohne Buildsystem.
- `src/main/resources/db/migration`: versionierte Oracle-SQL-Migrationen.

Die Quarkus-Plattform-BOM **3.40.1.1 LTS** verwaltet zueinander passende Erweiterungen (REST/Jackson, Oracle JDBC, Flyway, Mailer und Elytron). Java-Zielversion ist 21. Passwörter nutzt Elytron mit bcrypt, zufälligem Salt und Kostenfaktor 12; die 72-Byte-Grenze wird vor dem Hashing kontrolliert. Keine selbst implementierte Kryptografie. Zeitpunkte werden als Millisekunden seit Unix-Epoch in Oracle NUMBER(19) gespeichert; dadurch sind sie unabhängig von Oracle-Sitzungszeitzonen eindeutig. Dauern werden auf dem Server aus diesen Werten berechnet, erst die Anzeige rundet Stunden.

Für ungefähr 25 Personen ist eine einzige Datenbank-Zeile als kurze globale Schreibsperre verständlich und ausreichend: `SELECT … FOR UPDATE` serialisiert sämtliche fachlichen Änderungen. Überlappungsprüfung, höchstens ein Timer, Token-Einlösung, letzter Owner und Versionsprüfung erfolgen innerhalb derselben Transaktion. Ein funktionaler eindeutiger Oracle-Index schützt laufende Timer zusätzlich. Die Sperre funktioniert auch mit mehreren Java-Prozessen. Ein langsamer SMTP-Versand hält diese Sperre kurzzeitig länger; bei deutlich größerer Nutzung wäre eine eigene Versandarchitektur sinnvoll. Externe Mailzustellung und Datenbankcommit sind nicht atomar: Nach einem seltenen Commitfehler kann eine E-Mail einen ungültigen Link enthalten; ein erneuter Versand ersetzt ihn.

Jeder schreibende Eintrag erfordert eine Versionsnummer, damit Konflikte als HTTP 409 sichtbar werden. Für Neuanlage und Timerstart nutzt der Browser eine zufällige Request-ID; identische Wiederholungen erzeugen keinen zweiten Eintrag. Timerstop ist ebenfalls idempotent. Historische Zuordnungen sind durch Fremdschlüssel ohne kaskadierendes Löschen geschützt. Es gibt keine fachlichen Löschendpunkte.

Sitzungstokens bestehen aus 256 zufälligen Bits, stehen nur in HttpOnly/SameSite=Strict-Cookies und werden ausschließlich gehasht in Oracle gespeichert. Sitzungen laufen nach zwölf Stunden ab; Passwortwechsel, Reset, Rollenwechsel und Deaktivierung widerrufen Sitzungen. Produktiv sind Secure-Cookies und HTTPS zwingend. Authentifizierung wird im Requestfilter und für schreibende Aktionen nach Sperrerwerb nochmals geprüft. Mutationen verlangen eine exakt passende Origin und für angemeldete Aktionen zusätzlich ein sitzungsgebundenes CSRF-Token im Header. Keine Zugangstokens im Local Storage. Einladungs-/Resetlinks tragen ihr Token im URL-Fragment, das nicht an den Webserver übertragen und unmittelbar aus der Adresszeile entfernt wird. Kontoaktionen geben keine Passwort-Hashes oder Sitzungstokens aus.

Die Oberfläche setzt Benutzereingaben ausschließlich als Text. CSP, Same-Origin und parametrisierte Datenbankzugriffe schützen zusätzlich vor XSS und SQL-Injection. CSV-Felder werden gequotet und Formelpräfixe mit Apostroph entschärft. Persönliche E-Mails und Historie sind ausschließlich für Admin/Owner sichtbar; gemeinsame Übersichten enthalten Anzeigenamen. Die Historie protokolliert keine Passwörter oder geheimen Tokens. Sie ist für Administratoren lesbar, über die API nicht änderbar; mit getrenntem Laufzeitbenutzer zusätzlich nur SELECT/INSERT-Rechte. Das ist keine Signatur oder Manipulationssicherung gegenüber Datenbankadministratoren.

Login (10 pro Adresse / 15 Minuten, global 150), Reset-Anforderung (5 pro Adresse, global 50), Token-Einlösung (global 100), Einladung (40 pro Admin) und Bootstrap (10) sind in der Datenbank begrenzt, über Neustarts hinweg. Globale Grenzen sind eine bewusste einfache Lösung für das kleine Komitee; sie können durch Missbrauch die Anmeldung zeitweise blockieren. Zusätzlich kann der Reverse Proxy IP-basierte Limits verwenden. Rate-Limits werden in eigenen Transaktionen festgehalten, damit fehlgeschlagene Logins sie nicht zurückrollen. Reset-Anforderungen liefern dieselbe neutrale Antwort für existierende und unbekannte Konten, auch bei einem individuellen Versandfehler.

## Tests

```sh
mvn test
mvn package
```

Unit-Tests brauchen keine Datenbank. Sie prüfen Zeitregeln, DST, CSV-Schutz, Versionen, normalisierte E-Mails, Hashing und exakte Summierung. Ein erfolgreicher Unit-Test ist **kein Oracle-Kompatibilitätsnachweis**.

Die `OracleIT`-Tests benötigen ein **eigenes wegwerfbares Oracle-Schema**. Sie löschen vor jedem Test dessen Anwendungsdaten. Niemals Produktionszugangsdaten verwenden! `TEST_DESTRUCTIVE=true` ist eine zusätzliche ausdrückliche Freigabe nur für dieses Schema.

```sh
# DB_URL, DB_USER, DB_PASSWORD auf das ausschließlich für Tests angelegte Schema setzen.
export TEST_DESTRUCTIVE=true
mvn verify -DskipITs=false
```

Die Integrationstests nutzen Quarkus im Testprofil, echte Oracle-JDBC-Zugriffe und HTTP-Anfragen. SMTP wird durch Quarkus MockMailbox ersetzt. Sie prüfen Einladung/Einmaligkeit/Ablauf/Widerruf/Neuversand, Login, CSRF, Fremdzugriff, Timerkonkurrenz, Zeitüberschneidungen, Prüfung, Selbstgenehmigung, PENDING nach Änderung, Versionskonflikte, kombinierte Filter, historische Teams, Rollen, Sitzungswiderruf und Rate-Limits. Genau ausgeführte Checks und Grenzen: [Prüfprotokoll](docs/PRUEFUNG.md).

## Installation auf Schulserver, Konfiguration und Betrieb

Siehe die vollständige [deutsche Betriebsanleitung](docs/BETRIEB.md) für Oracle, SMTP, systemd, HTTPS, Netzwerk, Backup, Wiederherstellung und Updates.

Aktuelle offizielle Grundlagen zur Versionswahl und Konfiguration: [Quarkus Releases](https://quarkus.io/releases/), [Oracle-Datasource](https://quarkus.io/guides/datasource), [Flyway und Oracle-Modul](https://quarkus.io/guides/flyway), [Mailer](https://quarkus.io/guides/mailer-reference). Oracle-Voraussetzungen müssen für die konkret gewählte Datenbankversion und Hardware geprüft werden: [Oracle Free – System Requirements](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinl/requirements.html). Optionale lokale Container sind zusätzliche Software und keine Betriebsvoraussetzung; [Image-Maintainer-Dokumentation](https://github.com/gvenzl/oci-oracle-free) beschreibt ARM-Unterstützung ab 23.5. Oracle läuft nicht nativ als macOS-Programm; Docker/Podman benötigt dort eine Linux-VM und ausreichend Ressourcen. Für die Schule ist eine von der IT bereitgestellte Oracle-PDB der einfachste Weg.
