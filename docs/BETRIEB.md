# Betrieb von Time Tracker

## Voraussetzungen und Infrastruktur

Java-Anwendung: JDK 21+ für den Build, Java 21+ für das erzeugte Artefakt, Maven 3.9.12+ für den Build. Kein Node, kein Frontend-Build und keine Containerpflicht. Die Entwicklung wurde mit Java/Maven-Versionen im Prüfprotokoll durchgeführt. Für einen kleinen Linux-Java-Dienst zunächst 512 MB Java-Heap und zusätzlich Betriebssystem-/Prozessspeicher einplanen; konkrete Ressourcen unter eurer Last messen. Die Oracle-Datenbank ist davon getrennt zu dimensionieren.

Benötigt: Oracle-PDB/Service, DNS-Name, Linux-VM oder geeigneter LXC für Java, HTTPS-Reverse-Proxy und SMTP-Zugang. Oracle kann auf einem separaten Schulserver laufen. Oracle selbst in einem LXC wird hier nicht als unterstützte Installation vorausgesetzt. Ein Java-LXC genügt, wenn er die Oracle-Datenbank über das Schulnetz erreicht.

Oracle Free muss auf einer für die gewählte Version unterstützten Plattform installiert werden. Die [offiziellen Voraussetzungen](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinl/requirements.html) nennen für die dort dokumentierte Version mindestens 1 GB RAM (2 GB empfohlen), 10 GB Speicher und konkrete Linux-/CPU-Voraussetzungen. Das ist keine Empfehlung für die gesamte VM inklusive Java, Backups und Betriebssystem. Oracle auf Apple Silicon setzt eine passende ARM-Ausgabe und zusätzliche Linux-Virtualisierung voraus. Eine x86-Ausgabe läuft dort nicht ohne Emulation. Für Neuinstallationen mit Schul-IT und aktueller Oracle-Dokumentation abstimmen.

## Oracle-Benutzer und Migration

Als DBA in der **richtigen PDB** (etwa FREEPDB1), nicht im CDB-Root:

```text
sqlplus /nolog
CONNECT sys@//dbserver:1521/FREEPDB1 AS SYSDBA
@ops/create-schema.sql
```

SQL*Plus fordert Passwörter interaktiv an. Das Skript legt `ballzeit` mit nur CREATE SESSION, CREATE TABLE und begrenzter Tablespace-Quota an. Es werden weder SYS/SYSTEM für die Anwendung verwendet noch DBA-Rechte vergeben. Für entfernte Verbindungen über nicht vertrauenswürdige Netze TCPS/Oracle-Wallet oder abgesicherten Tunnel mit der Schul-IT konfigurieren; keine Datenbankpasswörter in Kommandozeilen schreiben.

Die Anwendung verwendet `jdbc:oracle:thin:@dbserver:1521/SERVICE`. Verfügbarkeit, Oracle-Version und Service-Namen vom DBA bestätigen lassen. Fremdschlüssel schützen historische Daten. Das initiale Schema liegt unter `src/main/resources/db/migration/V1__initial.sql`. Beim ersten Start migriert Flyway automatisch. `clean` ist deaktiviert. Bereits ausgeführte Migrationen niemals ändern; neue V2-, V3-Dateien ergänzen. Oracle-DDL hat implizite Commits: fehlgeschlagene Migrationen fachlich prüfen, Ursache beheben, ggf. DBA-gestützt reparieren. Nicht blind `flyway repair` ausführen.

Optional für striktere Rechte: nach der Migration [runtime-grants.sql](../ops/runtime-grants.sql) als DBA ausführen. Danach `DB_USER=ballzeit_runtime`, `DB_MIGRATE=false` und

```text
QUARKUS_DATASOURCE_JDBC_NEW_CONNECTION_SQL=ALTER SESSION SET CURRENT_SCHEMA=BALLZEIT
```

setzen. `DB_PASSWORD` ist dann das Laufzeitpasswort. Die Anwendung verwendet kein öffentliches Synonym. Der Laufzeitbenutzer darf keine Tabellen löschen und keine Audit-Ereignisse ändern oder löschen. Updates/Migrationen gesondert mit dem Schema-Benutzer vornehmen (siehe Updates); dessen Zugangsdaten nicht dauerhaft im Laufzeitdienst lassen.

## Konfiguration

Lokal liest Quarkus `.env`; auf dem Server eine rootverwaltete systemd-EnvironmentFile `/etc/ballzeit/ballzeit.env` (0600, Zugriff nur für Administratoren). Nicht beide Dateien mit widersprüchlichen Werten verwenden. Keine Datei mit echten Werten ins Repository oder unverschlüsselte Backups legen.

| Variable | Bedeutung |
| --- | --- |
| `DB_URL` | Oracle JDBC-URL mit Host, Port und PDB-Service |
| `DB_USER`, `DB_PASSWORD` | Datenbank-Anwendungsbenutzer und Geheimnis |
| `DB_MIGRATE` | Standard `true`; bei eingeschränktem Laufzeitbenutzer `false` |
| `PUBLIC_URL` | Exakte Origin ohne Unterpfad, Query oder Fragment, z. B. `https://ballzeit.eure-schule.at`; keine abschließenden zusätzlichen Pfade |
| `BOOTSTRAP_KEY` | Zufälliger geheimer Einrichtungsschlüssel, mindestens 32 Zeichen; nach Ersteinrichtung entfernen |
| `MAIL_ENABLED` | Produktiv erst nach echter SMTP-Konfiguration `true`; sonst liefern Einladungen/Reset-Anfragen ausdrücklich 503 |
| `SMTP_HOST`, `SMTP_PORT` | SMTP-Host und Port; Standard-Port 587 mit verpflichtendem STARTTLS |
| `SMTP_FROM` | Vom Provider erlaubte Absenderadresse |
| `SMTP_USER`, `SMTP_PASSWORD` | SMTP-Anmeldung; nur Serverkonfiguration |
| `QUARKUS_HTTP_HOST` | Standard 127.0.0.1; nur bei bewusst getrenntem Proxy ändern |
| `QUARKUS_HTTP_PORT` | Standard 8080 |
| `QUARKUS_DATASOURCE_JDBC_NEW_CONNECTION_SQL` | Optionales CURRENT_SCHEMA für getrennten Laufzeitbenutzer |

Weitere Quarkus-Konfiguration ist über dokumentierte QUARKUS_* Umgebungsvariablen möglich. Sicherheitsvorgaben des Produktionsprofils nicht abschwächen. Die Anwendung lehnt eine HTTP-PUBLIC_URL oder deaktivierte Secure-Cookies im Produktionsstart ab. Unterpfad-Hosting wird nicht unterstützt; eigene Origin nutzen. Der Proxy muss den `Origin`-Header unverändert weiterreichen.

## SMTP

Im Dev-/Testprofil wird Quarkus MockMailer verwendet. Diese Profile dürfen E-Mail-Inhalte inklusive Links lokal zeigen. Keine Freigabe von Entwicklungslogs an Unberechtigte.

Produktion: MAIL_ENABLED=true, SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USER, SMTP_PASSWORD konfigurieren; Mock muss false bleiben. SMTP über STARTTLS ist verpflichtend. Provider muss TLS mit gültigem Zertifikat unterstützen. Keine `trust-all`-Konfiguration verwenden. Für reines TLS auf Port 465 ausdrücklich `QUARKUS_MAILER_TLS=true` und `QUARKUS_MAILER_START_TLS=DISABLED` setzen, passend zum Provider; auf Port 587 bleiben die sicheren Defaults. Gegebenenfalls `QUARKUS_MAILER_LOGIN=REQUIRED` setzen. Bei eigenen Schul-Zertifikaten den passenden Truststore nach [Quarkus Mailer-Dokumentation](https://quarkus.io/guides/mailer-reference) konfigurieren.

Nach der Einrichtung eine Einladung an ein kontrolliertes Postfach senden, den Link über die öffentliche Domain öffnen, Einmaligkeit und Neuversand prüfen. Erfolgreiche SMTP-Annahme beweist nicht den tatsächlichen Postfacheingang: Spam, DKIM/SPF und Providerzustellung prüfen. Bei Versandfehlern wird die Einladungsänderung zurückgerollt, damit keine angeblich versendete Einladung gespeichert wird. Reset-Antworten bleiben zum Schutz vor Account-Ermittlung neutral; ein technischer Versandfehler erzeugt eine Warnung ohne E-Mail-Adresse oder Token. Bei deaktiviertem Mailversand ist für alle Reset-Anfragen ein einheitlicher 503 sichtbar.

## Build, Start und erster Owner

```sh
mvn verify
```

Das kopierbare Ergebnis ist der **vollständige Ordner `target/quarkus-app`**, nicht nur eine einzelne JAR. In ein versioniertes Verzeichnis `/opt/ballzeit/releases/<version>/quarkus-app` kopieren und einen Symlink `/opt/ballzeit/current` auf dieses Release-Verzeichnis setzen.

```sh
java -jar target/quarkus-app/quarkus-run.jar
```

Für diesen Produktionsstart müssen die Umgebungsvariablen tatsächlich im Prozess gesetzt sein (z. B. über systemd). Die Dev-Mailkonfiguration gilt hier nicht.

Ersten Owner über **Erste Einrichtung** in der Oberfläche anlegen. Dazu den zufälligen BOOTSTRAP_KEY über einen sicheren administrativen Kanal bereitstellen, Anzeigenamen und persönliches Schulkürzel eingeben, ein eigenes Passwort (nicht das Schulpasswort) wählen und Einrichtung abschließen. Neue Konten starten ohne Team; Admin/Owner erstellt Teams später und weist sie unter Mitglieder zu. Dann Schlüssel aus `/etc/ballzeit/ballzeit.env` entfernen und Dienst neu starten. Der Schlüssel allein reicht nach erfolgreichem Bootstrap nicht erneut aus: `app_lock.bootstrapped=1` bleibt dauerhaft bestehen. Ein Neustart erzeugt keine neuen Benutzer. Keine öffentliche Selbstregistrierung; alle weiteren Konten brauchen Einladung und werden zunächst MEMBER. Owner ernennt nach Bedarf einen zweiten Owner oder Admin. Admins und Owner können auch eigene Zeiten bestätigen. Zusätzliche berechtigte Personen können nach Bedarf ernannt werden.

## Dauerhafter Dienst

Dedizierten Systembenutzer `ballzeit` ohne Login anlegen. Java-Artefakte sollen rootgehören und für den Dienst nur lesbar sein. `/etc/ballzeit` anlegen; EnvironmentFile rootverwaltet 0600. [ballzeit.service](../ops/ballzeit.service) nach `/etc/systemd/system/ballzeit.service` übernehmen, Java-Pfad und Release-Verzeichnis prüfen.

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now ballzeit
sudo systemctl status ballzeit
sudo journalctl -u ballzeit --since today
```

Logs dürfen produktiv keine Links oder Tokens enthalten. Der allgemeine Fehlerhandler gibt keine internen Exception-Details aus und loggt nur die Fehlerklasse. Datenbank-/SMTP-Administratoren können eigene technische Logs haben; Zugriff und Aufbewahrung entsprechend beschränken.

## Reverse Proxy und HTTPS

[nginx.conf](../ops/nginx.conf) ist ein Beispiel mit zu ersetzender Domain und Zertifikatpfaden. HTTPS-Zertifikat über eure Schul-IT oder ACME-Verfahren beziehen, Erneuerung automatisieren und testen. DNS und `PUBLIC_URL` müssen dieselbe Origin ergeben. Intern lauscht Java nur auf 127.0.0.1:8080. Der Browser erhält Secure-, HttpOnly- und SameSite=Strict-Cookies. HTTP wird am Proxy auf HTTPS umgeleitet. Auf derselben Domain `/api` und das statische Frontend ausliefern. Keine CORS-Freigabe und kein direkt öffentlicher Java-Port erforderlich. `/q/` ist im Proxy gesperrt.

Der Proxy vertraut keinen eingehenden Forwarded-For-Werten; er setzt die Clientadresse selbst. Die Anwendung verwendet keine durch Header behauptete Client-IP für ihre Sicherheitsentscheidungen. `PUBLIC_URL` dient dem Origin-Abgleich und der Linkbildung, nicht dem möglicherweise manipulierten Host-Header.

## Netzwerkfreigaben

- Browser → Reverse Proxy: TCP 443, ggf. TCP 80 für Redirect/ACME.
- Reverse Proxy → Java: 127.0.0.1 TCP 8080; bei getrennten Hosts nur gezielt zwischen ihnen freigeben.
- Java → Oracle: der vom DBA gewählte Listener-Port, häufig TCP 1521 bzw. TCPS-Port.
- Java → SMTP: TCP 587 oder ausdrücklich konfigurierte TLS-Verbindung.
- DNS/NTP und ggf. Zertifikatserneuerung gemäß Schulnetz. Genaue Uhrzeitsynchronisierung ist Voraussetzung für Timer, Tokenablauf und Sitzungen.

Oracle und Java-Port nicht ins Internet veröffentlichen. SSH nur für zuständige Administratoren. Für LXC Host-/Container-Firewall und Routing gemeinsam prüfen.

## Backup

Alle fachlichen Daten einschließlich laufender Timer, Sitzungen, Token-Hashes, Bootstrapkennzeichen und Historie liegen in Oracle. Neustarts verlieren keine Daten. Die Java-Anwendung schreibt keine eigene Datenbankdatei.

DBA soll regelmäßige Oracle-Backups mit Data Pump (Schemaexport) oder RMAN entsprechend Schulstandard einrichten. Beispiel (Directory-Objekt und Pfad vom DBA erstellen lassen, Exportrecht eng begrenzen):

```text
expdp ballzeit@//dbserver:1521/FREEPDB1 schemas=BALLZEIT directory=BALLZEIT_BACKUP dumpfile=ballzeit_YYYYMMDD.dmp logfile=ballzeit_YYYYMMDD.log flashback_time=systimestamp
```

Passwort interaktiv eingeben. Die Verzeichnisse liegen auf dem Datenbankserver; Java-VM-Dateisicherungen allein sichern Oracle nicht. Für konsistente Online-Exporte die benötigten Oracle-Rechte und Flashback/Undo-Verfügbarkeit mit DBA prüfen. Alternative: Dienst kurz stoppen und konsistenten DBA-Export durchführen. Laufende Timer laufen nach Wiederherstellung zeitlich weiter; nach einem längeren Ausfall sorgfältig prüfen und korrigieren.

Zusätzlich sichern: vollständiges freigegebenes Release, versionierte Migrationen, diese Dokumentation, systemd-/Proxy-Konfiguration, verschlüsselte EnvironmentFile/SMTP-Zugänge, notwendige Zertifikats-/Wallet-Konfiguration. Rechte 0600, verschlüsselte Ablage, getrenntes Backupziel, dokumentierte Aufbewahrungsfrist und Verantwortlicher. Backups enthalten personenbezogene Daten und Passwort-Hashes und brauchen denselben Zugriffsschutz wie die Datenbank. Regelmäßige Wiederherstellungsprobe in isolierter Umgebung ist Teil des Betriebs.

## Wiederherstellung

1. Dienst stoppen, betroffenen Stand sichern, Ursache und richtigen Sicherungszeitpunkt bestimmen.
2. Oracle/PDB in kompatibler Version bereitstellen, Schema-Benutzer/Quota nach DBA-Plan anlegen. Oracle-Export mit `impdp` importieren. Nicht in eine aktive Produktionsdatenbank importieren. Beispiel:

   ```text
   impdp ballzeit@//dbserver:1521/FREEPDB1 schemas=BALLZEIT directory=BALLZEIT_BACKUP dumpfile=ballzeit_YYYYMMDD.dmp logfile=restore_YYYYMMDD.log
   ```

3. Dazu passendes Java-Release und gesicherte Konfiguration wiederherstellen; zunächst isoliert mit angepasster PUBLIC_URL, ohne echten Mailversand starten. Flyway-History aus dem Backup muss erhalten bleiben.
4. Als DBA bei Bedarf alle Sitzungen widerrufen (`DELETE FROM ballzeit.sessions`) und offene Tokens widerrufen (`UPDATE ballzeit.account_tokens SET revoked_at=<aktuelle Unix-Millisekunden> WHERE used_at IS NULL`). Diese Notfallaktion außerhalb der Anwendung mit Akteur/Datum im Betriebsprotokoll festhalten. Bootstrapkennzeichen erhalten, keinen neuen Owner automatisch anlegen.
5. Login, Berechtigungen, Stichproben historischer Einträge/Summen, offene Timer und Historie prüfen. Bei geplanten Versionsupdates erst danach neue Migrationen anwenden. Laufzeitrechte und Mailkonfiguration prüfen, öffentlichen Dienst freigeben.

Die konkreten Oracle-Wiederherstellungsbefehle hängen von PDB, Exportbenutzer, Tablespace und DBA-Backupstrategie ab; diesen Ablauf vor Produktivbetrieb einmal mit der Schul-IT durchspielen. Backup-Import und Serverbetrieb sind nicht durch lokale Anwendungstests abgedeckt.

## Updates

Neues Release getrennt bauen, Unit- und echte Oracle-Integrationstests gegen wegwerfbares Schema ausführen. Datenbankbackup und alte Release-Artefakte sichern. Dienst stoppen, neue Migrationen mit Schema-Benutzer ausführen (neues Release temporär mit DB_MIGRATE=true und dessen Zugangsdaten starten, Migrationserfolg kontrollieren, wieder stoppen). Danach ausschließlich eingeschränkte Laufzeit-Zugangsdaten und DB_MIGRATE=false verwenden, ggf. Rechte für neue Tabellen ergänzen. Release-Symlink wechseln, Dienst starten und Browser-Smoke-Test durchführen.

Flyway führt nur neue versionierte Dateien aus. Ein Code-Rollback ist nur möglich, wenn das alte Release mit dem neuen Schema kompatibel ist. Andernfalls gemeinsam Schema-Backup und passendes Release wiederherstellen; nach Backup entstandene Daten nicht unbemerkt verlieren. Nie nur den Flyway-History-Eintrag löschen, um eine Migration zu wiederholen. Quarkus-/Oracle-/Java-Sicherheitsupdates regelmäßig einplanen und vor Übernahme testen.

## Typische Fehler

| Symptom | Prüfung / Behebung |
| --- | --- |
| ORA-12514 / Listener-Fehler | PDB geöffnet? Service-Name und DB_URL stimmen? Nicht SID statt Service verwenden. |
| ORA-01017 | DB_USER/DB_PASSWORD und richtige PDB kontrollieren, keine Geheimnisse ins Fehlerprotokoll kopieren. |
| ORA-01031 / Quota | CREATE TABLE bei Migration, Tablespace-Quota und Laufzeit-Grants prüfen. Kein pauschales DBA-Recht vergeben. |
| Flyway-Checksumme verändert | Ursprüngliche Migration wiederherstellen; Änderungen als neue Migration schreiben. |
| Produktion startet mit PUBLIC_URL-Fehler nicht | Exakte HTTPS-Origin ohne Pfad eintragen, Secure-Cookies beibehalten. |
| HTTP 403 beim Speichern | Browser-Origin muss exakt PUBLIC_URL sein; nicht zwischen localhost/127.0.0.1 oder Ports wechseln. Neu laden für frischen CSRF-Wert. |
| Login auf Produktion „vergessen“ | HTTPS und Cookie-Secure prüfen. Anwendung nicht direkt über HTTP bedienen. |
| HTTP 409 | Daten wurden parallel geändert, Zeit überschneidet sich, ein Timer läuft oder letzter Owner wäre betroffen; Meldung beachten und neu laden. |
| HTTP 429 | 15-Minuten-Fenster abwarten. Kein Neustart zur Umgehung; Limits liegen in Oracle. |
| Einladung/Reset ohne SMTP | Lokal Dev-Profil mit MockMailer; produktiv echten Transport konfigurieren. MAIL_ENABLED=false meldet ausdrücklich 503. |
| Einladungslink abgelaufen | Neu versenden; früherer Link wird widerrufen. Neue Konten starten ohne Stammteam; ein Admin/Owner weist es nachträglich zu. |
| Diagramm leer | Status und Zeitraum prüfen. PENDING zählt nicht zu APPROVED. Filter beziehen sich auf Beginn und bearbeitetes Team. |
| Zeitumstellung schlägt fehl | Frühlingslücke existiert nicht. Doppelte Herbststunde braucht +02:00 oder +01:00. |
| Timer nach Ausfall sehr lang | Er läuft fachlich weiter. Eintrag nach dem Stoppen korrigieren; Änderung bleibt nachvollziehbar. |

## Vor Schulserver-Freigabe noch festzulegen

Verantwortlicher, Linux-/Java-Version, Oracle-Version/PDB/Standort/Backups, DNS/Domain, Zertifikatsbetrieb, SMTP-Zugang/Absender, Firewall, Speicher-/Logaufbewahrung, Datenschutz-/Zugriffsregeln und Zeit für eine gemeinsame Wiederherstellungsprobe. Nach Konfiguration echte Mailzustellung, HTTPS-Cookies, Einladung über öffentliche Domain, Dienst-Neustart mit laufendem Timer und Restore auf dem Zielsystem prüfen. Lokale Tests ersetzen diese Abnahme nicht.


## Update auf Projektvorlagen und Darstellung (V4)

Migration V4 ergänzt Projektkonfiguration, persönliche Farb-/Moduswahl und die Freigabepflicht je Eintrag. Bestehende Daten bleiben erhalten. Nach einer Migration mit getrenntem Laufzeitbenutzer zusätzlich `GRANT SELECT, UPDATE ON ballzeit.project_settings TO ballzeit_runtime` vergeben; das aktualisierte runtime-grants.sql enthält dies bereits. Der Oracle-Schemaname, interne Java-Paketname und Konfigurationspräfix `ballzeit.*` bleiben zur Kompatibilität unverändert; die Benutzeroberfläche heißt Time Tracker. Die Anwendung bleibt ein einzelnes gemeinsames Projekt pro Installation.

Persönliche Darstellungen und die Projektvorlage werden in Oracle gespeichert und sind daher durch das Datenbankbackup mitgesichert. Die Vorlagen ohne Freigabepflicht erzeugen direkt gültige Einträge ohne menschlichen Entscheider; das ist durch `approval_required=0` im Datensatz und CSV erkennbar. Ältere freigabepflichtige Einträge behalten ihre Prüfpflicht auch nach einem Vorlagenwechsel.
