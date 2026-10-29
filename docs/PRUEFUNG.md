# Prüfprotokoll

Stand: **29. Oktober 2026**, Europe/Vienna. Die folgenden Angaben beziehen sich auf tatsächlich ausgeführte Prüfungen des gelieferten Standes. Sie sind keine pauschale Produktionsfreigabe.

## Umgebung

- macOS auf Apple Silicon (aarch64).
- Maven 3.9.12, dessen JDK 25.0.1; Kompilierziel Java 21. Der separat gestartete Java-Prozess verwendete OpenJDK 25.0.2. Ein Start auf einem tatsächlich installierten JDK 21 wurde hier nicht durchgeführt.
- Quarkus-Plattform 3.40.1.1 LTS, vom Maven-Build erfolgreich aufgelöst und gebaut.
- **Echte Oracle Free 23.26**, Container `gvenzl/oracle-free:23-slim`, ARM-Ausgabe. Verwendeter Image-Digest: `sha256:6d61d267a3b978c24c5ac1790e62e927416a0aec446bd86e4b3a1527562757bd`. Nur localhost-Port 15219.
- Getrennte Schemas BALLZEIT_TEST (automatisch zurückgesetzt), BALLZEIT_DEV (ausdrücklich aktivierte Entwicklungsdaten für Browser-QA) und BALLZEIT_LOCAL (leeres Schema für die eigene Ersteinrichtung).
- Safari 27.0.1, Desktop und Responsive-Design-Modus 390 × 844 Pixel. Kein physisches Smartphone, keine zusätzliche Browserengine.

## Automatisierte Tests: erfolgreich

Vollständiger Befehl mit DB-Umgebungsvariablen auf das wegwerfbare Testschema und `TEST_DESTRUCTIVE=true`:

```sh
mvn -B -ntp verify -DskipITs=false
```

Ergebnis: **BUILD SUCCESS**, **12 Unit-Tests + 35 Oracle-Integrationstests**, keine Fehler, keine übersprungenen Tests. Alle vier versionierten Flyway-Migrationen wurden erfolgreich gegen Oracle angewendet und danach validiert. Maven hat das ausführbare Artefakt unter `target/quarkus-app` erzeugt.

Unit-Tests:

1. Zeitintervall: Ende nach Beginn, keine Zukunft, kein Nullintervall.
2. Überlappung und erlaubte direkt benachbarte Intervalle.
3. Nicht existierende Uhrzeit beim Frühlingswechsel.
4. Doppelte Herbststunde und ausdrücklich gewählter Offset.
5. Unpassender UTC-Offset wird abgelehnt.
6. Dauer über Mitternacht.
7. CSV-Formelinjektion einschließlich Leerraum, Steuerzeichen, mehrzeiligem Formeltext und Anführungszeichen.
8. E-Mail-Normalisierung, Text- und Passwortlängen, UTF-8-Bytegrenze.
9. Konflikt durch veraltete Versionsnummer.
10. Zufällige Tokens, SHA-256-Tokenhashes und gesalzenes bcrypt.
11. Exakte Summierung von Millisekunden ohne Rundungsfehler.

Oracle-HTTP-Integrationstests:

1. Login, Logout, sichere Cookie-Eigenschaften im Testprofil und keine geheimen Benutzerfelder.
2. Anonymer Zugriff, Origin-Prüfung, CSRF und Rollen-/E-Mail-Datenschutz.
3. Mitglied kann fremden Eintrag nicht ändern.
4. Idempotente Anlage, Mitternacht, Überlappung und direkt angrenzende Zeiten.
5. Ungültige, zukünftige, DST-Lücken- und leere Beschreibungswerte.
6. Zwei echte parallele Timerrequests, Wiederabruf, idempotenter Stop und genau ein Eintrag.
7. Manuelle Zeit darf laufenden Timer nicht überschneiden.
8. Genehmigung und Verbot der Selbstgenehmigung eines Admins.
9. Mitglied darf nicht prüfen, Ablehnungsbegründung nötig, Statussummen getrennt.
10. Adminänderung setzt Freigabe zurück; frühere Entscheidung bleibt in Historie; alte Version scheitert.
11. Kombinierte Datums-/Personen-/Team-/Aufgaben-/Statusfilter, passende Summen und Export; Stammteamwechsel ändert historischen Eintrag nicht.
12. Einladung einmalig und normalisierte doppelte E-Mail gesperrt; Klartexttoken nicht gespeichert.
13. Ablauf, Widerruf und Neuversand machen alte Einladungslinks ungültig.
14. Passwort-Reset einmalig und widerruft Sitzungen; unbekanntes Konto erhält neutrale Antwort.
15. Letzter aktiver Owner geschützt, Admin darf keine Rollen vergeben, Deaktivierung sperrt Sitzungen und Login.
16. Archivierte Aufgabenbereiche bleiben historisch sichtbar, sind für neue Einträge gesperrt, FK verhindert zerstörendes Löschen.
17. Login-Rate-Limit bleibt trotz fehlgeschlagener Logintransaktionen wirksam.
18. Bootstrap mit falschem Schlüssel abgelehnt, genau einmal erfolgreich, auch nach Entfernung des erstellten Owners nicht erneut möglich.
19. Gleichzeitige manuelle Erfassungen können sich nicht überschneiden.
20. Bearbeitung darf keine Überschneidung erzeugen.
21. Deaktivierung schließt laufenden Timer als PENDING ab und widerruft Zugriff.
22. Reset-Neuversand und -Ablauf machen Links ungültig.
23. Oracle selbst erzwingt durch eindeutigen funktionalen Index höchstens einen Timer.

Zusätzlich erfolgreich: JavaScript-Syntaxprüfung (`node --check`, nur Prüfwerkzeug, keine Node-Anwendung) und `git diff --check`. Die Anwendung besitzt keine Node-Abhängigkeit; JavaScript/CSS wurden mit einem temporären Formatter lesbar formatiert.

## Browserprüfung: erfolgreich

Über die echten Formulare in Safari, mit zufällig angelegten Testzugängen und ausschließlich aktivem Dev-Mail-Mock:

- Anmeldung, Dashboard, Stundenkarten, Aufgabenfarbdiagramm und textliche Aufschlüsselung.
- Timer mit Aufgabenbereich/Beschreibung gestartet.
- **Java-Anwendung vollständig beendet und neu gestartet**, danach Browser neu geladen: Sitzung blieb gültig, derselbe Timer lief mit korrektem servergespeicherten Start weiter (beobachtet nach Wiederladen: 48 Sekunden).
- Timer gestoppt, genau ein abgeschlossener PENDING-Eintrag erschien.
- Manuelle Zeit vom 10. Januar 2026, 23:00 bis 11. Januar 2026, 01:00 eingegeben und gespeichert: 2,0 Stunden, PENDING.
- Prüfungsansicht: eigene Auswahl deaktiviert, fremden Eintrag ausdrücklich ausgewählt, Bestätigungsdialog und Freigabe erfolgreich; Eintrag verschwand aus der PENDING-Auswahl.
- Gemeinsame Ansicht: APPROVED als Standard; kombinierte Auswahl Anna + bearbeitetes Team Klasse 5B ergab 2,5 Stunden und genau einen Eintrag. Diagramm, Textaufschlüsselung und Summe stimmten überein.
- CSV über Browser heruntergeladen. Die Datei wurde zusätzlich mit einem CSV-Parser geprüft: genau dieser eine Eintrag, Team Klasse 5B, Status APPROVED, Dauer 9000.000 Sekunden.
- Desktoplayout visuell kontrolliert; mobiles Dashboard und Filterbedienung im Responsive-Design-Modus bei 390 × 844 Pixeln kontrolliert. Tabellen sind innerhalb ihrer Bereiche horizontal scrollbar, Navigation ebenfalls.

Einladungsannahme und Passwort-Reset wurden durch die automatisierten HTTP-Tests mit MockMailbox, nicht zusätzlich als vollständige Browserreise ausgeführt. Kein pauschaler Accessibility- oder vollständiger Mehrbrowser-Test.

## Gebautes Produktionsartefakt: lokaler Smoke-Test erfolgreich

`java -jar target/quarkus-app/quarkus-run.jar` wurde im normalen Produktionsprofil gegen Oracle auf 127.0.0.1:8082 gestartet. Als konfigurierte öffentliche Origin diente ausschließlich für den Test `https://ballzeit.invalid`; es wurde keine externe Verbindung zu dieser Domain hergestellt und kein echter Reverse Proxy eingerichtet.

Geprüft:

- Produktionsprozess startet erfolgreich, Frontend antwortet 200.
- `/q/dev-ui/` ist 404.
- Gemeinsame API ohne Sitzung ist 401.
- Loginantwort setzt **Secure; HttpOnly; SameSite=Strict**.
- Abweichende Origin wird mit 403 abgelehnt.
- Reset-Anforderung bei deaktiviertem SMTP liefert ausdrücklich 503.

Dieser Test ersetzt keine Prüfung einer echten TLS-Verbindung über den Schulproxy. Die Produktionsprüfung nutzte keine echten SMTP-Zugangsdaten und versendete keine E-Mails.

## Noch nicht auf Zielinfrastruktur geprüft

- Echter SMTP-Server, Zustellung/Spam/DKIM/SPF, öffentliche Einladungs- und Resetlinks.
- Java 21 als tatsächlich installierte Laufzeit; Ziel-Linux, systemd, LXC-Netzwerk, Reverse Proxy, DNS und HTTPS-Zertifikatserneuerung.
- Schul-Oracle-Version, dortige Benutzerrechte und der optionale getrennte Laufzeitbenutzer mit CURRENT_SCHEMA. Die enthaltenen Rechteskripte müssen vom zuständigen DBA auf seine PDB/Tablespaces angepasst und dort getestet werden.
- Data-Pump/RMAN-Backup und echte Wiederherstellung. Dafür sind Skripte/Ablauf und eine Probe mit der Schul-IT nötig.
- Belastung mit 25 gleichzeitig aktiv bedienenden Personen. Die entscheidenden Konkurrenzfälle wurden durch parallele Requests geprüft, aber kein Langzeitlasttest durchgeführt.
- Physische Android-/iOS-Geräte, Chromium/Firefox, assistive Technologien und externer Sicherheitstest.

Konkrete weitere Abnahme: Serverdaten und SMTP setzen, Anleitung in BETRIEB.md durchgehen, zweite berechtigte Person einladen, gegenseitige Freigabe prüfen, Timer über Dienst-Neustart prüfen und einen gesicherten Stand in einer isolierten PDB wiederherstellen. Es wurden keine erfolgreichen Prüfungen dieser noch offenen Punkte behauptet.

Der abschließende lokale Schnellstart wurde zusätzlich mit der vorbereiteten `.env` gegen BALLZEIT_LOCAL ausgeführt: zwei Migrationen erfolgreich, Frontend HTTP 200, anonymer Kontozugriff 401. Direkte lesende Oracle-Abfragen bestätigten **0 Mitglieder, 0 Zeiteinträge und Bootstrapkennzeichen 0**. Die Anwendung wartet damit auf die persönliche Ersteinrichtung des Nutzers. Der Entwicklungs-Java-Prozess wurde nach den Prüfungen beendet; die lokale Oracle-Instanz bleibt zum Starten verfügbar.


## Änderung: Schulkürzel und zunächst kein Team

Nach der Anpassung erneut vollständig ausgeführt: `mvn -B -ntp verify -DskipITs=false`, **BUILD SUCCESS, 37 Tests (12 Unit + 25 Oracle), keine Fehler oder übersprungenen Tests**.

Zusätzlich geprüft: verpflichtendes und normalisiertes Schulkürzel, Zurückweisung doppelter Kürzel, neue eingeladene Mitglieder und erster Owner ohne Stammteam, Teamzuweisung und Entfernen durch Owner, Verbot von Teamerstellung und fremder Teamzuweisung durch Mitglieder. Die nicht destruktive Migration V3 wurde auch in der laufenden lokalen Anwendung erfolgreich angewendet. Vorhandene Konten dürfen zunächst ein unbekanntes Schulkürzel (NULL) behalten; neue Konten müssen es angeben.

In Safari wurde die aktualisierte Owner-Einrichtung geöffnet: Schulkürzelfeld vorhanden, kein Teamfeld, Hinweis auf spätere administrative Teamzuweisung und deutlicher Hinweis „Verwende NICHT dein Schulpasswort“. Es wurde dabei kein persönliches Owner-Konto für den Nutzer angelegt; die Einrichtung bleibt ihm überlassen.


## Time Tracker: Vorlagen, Darstellung und neue Auswertungen

Nach der Umstellung erneut ausgeführt: `mvn -B -ntp verify -DskipITs=false`, **BUILD SUCCESS, 47 Tests (12 Unit + 35 Oracle), keine Fehler und keine übersprungenen Tests**. Die Migration V4 wurde auf echtem Oracle angewendet. Das anschließende Packaging übernimmt die zuletzt formatierten statischen Dateien; dafür wurden die zuvor erfolgreichen Backend-Tests nicht nochmals wiederholt.

Neue Oracle-Abdeckung: alle acht Farb-/Moduskombinationen einschließlich Speicherung und Versionskonflikt, ausschließlich dem zugewiesenen Team zugeordnete neue Zeiten, Zurückweisung manipulierter Teamwerte, frei eingegebene Tätigkeiten und Wiederverwendung vorhandener Namen, notwendige Admin-Teamzuweisung bei Teamvorlagen, Freies Projekt ohne Team und ohne menschlichen Prüfer, Beibehaltung früherer Freigabepflichten nach Vorlagenwechsel, nur Owner dürfen die Projektvorlage ändern, vier öffentliche Vorlagendefinitionen, Alle-Status-Filter mit getrennten Summen und laufenden Timern ohne Dauer, exakte Tages-/Wochentags-/Durchschnittsstatistiken, direkt erfasste Timerabschlüsse und Deaktivierung sowie historische Teamzuordnung eines Timers nach Teamwechsel. Die Ersteinrichtung wurde zusätzlich mit ausdrücklich gewählter Jahresprojekt-Vorlage geprüft.

Browserprüfung in Safari auf getrenntem Entwicklungsschema unter 127.0.0.1:8083 (Sitzung und Einstellungen des tatsächlichen Projekts auf localhost:8080 wurden nicht verändert):

- Time-Tracker-Branding und Lila/Pink-Hellmodus als Standard visuell geprüft.
- Farb-/Modusauswahl und Vorlagenkarten unter Einstellungen geöffnet. Dunkelmodus gespeichert und nach vollständigem Neuladen wiederhergestellt; lesbare dunkle Oberflächen visuell geprüft.
- Neue Statusauswahl per Tastatur bedient: Alle Status gewählt, anschließend erst nach Filter anwenden aktualisiert. Getrennte gültige/wartende/abgelehnte Summen sichtbar.
- Personen-Mehrfachauswahl per Tastatur und Teamfilter kombiniert: Anna UND Klasse 5B ergab genau einen Eintrag und 2,5 Stunden; Tätigkeits-, Personen-, Team- und Wochentagsdiagramm zeigten dieselbe Auswahl.
- Mobile Ansicht bei 390 × 844 Pixeln geprüft. Feste Teamanzeige, freie neue Tätigkeit Social Media und Beschreibung ausgefüllt, Timer über die echte Oberfläche gestartet und gestoppt. Der Eintrag behielt das zugewiesene Team und erschien als PENDING.
- Die Kopfzeile sitzt auch auf kurzen Seiten oben; Auswahlmenüs und optionale Datumsgrenzen wurden überarbeitet. Kontrast der normalen Hilfstextfarben im Hellmodus rechnerisch auf mindestens 4,5:1 zum jeweiligen Seitenhintergrund geprüft. Kein vollständiger Accessibility-Audit.

Die serverseitige Projektkonfiguration und persönliche Darstellung gehören zum Oracle-Backup. Bereits vorhandene Datensätze wurden durch die Migration nicht gelöscht. Physische Smartphones und weitere Browserengines wurden weiterhin nicht geprüft.
