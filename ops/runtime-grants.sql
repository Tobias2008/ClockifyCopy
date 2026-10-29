-- Optional nach abgeschlossener Flyway-Migration als DBA in derselben PDB.
-- Laufzeitbenutzer kann keine Tabellen erzeugen/löschen oder die Historie verändern.
SET VERIFY OFF
ACCEPT runtime_password CHAR PROMPT 'Neues Passwort für ballzeit_runtime: ' HIDE
CREATE USER ballzeit_runtime IDENTIFIED BY "&runtime_password";
GRANT CREATE SESSION TO ballzeit_runtime;
GRANT SELECT, UPDATE ON ballzeit.app_lock TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.members TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.teams TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.activities TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.time_entries TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.account_tokens TO ballzeit_runtime;
GRANT SELECT, INSERT, DELETE ON ballzeit.sessions TO ballzeit_runtime;
GRANT SELECT, INSERT, UPDATE ON ballzeit.rate_limits TO ballzeit_runtime;
GRANT SELECT, INSERT ON ballzeit.audit_events TO ballzeit_runtime;
-- CURRENT_SCHEMA lässt die unveränderten SQL-Abfragen auf das Schema ballzeit zeigen.
-- Um nicht alle Objekte durch öffentliche Synonyme zugänglich zu machen, beim Verbindungsaufbau:
-- QUARKUS_DATASOURCE_JDBC_NEW_CONNECTION_SQL=ALTER SESSION SET CURRENT_SCHEMA=BALLZEIT
-- DB_USER=ballzeit_runtime und DB_MIGRATE=false setzen.
UNDEFINE runtime_password
-- Seit Migration V4: Projektname, Vorlage und Wochenziel.
GRANT SELECT, UPDATE ON ballzeit.project_settings TO ballzeit_runtime;
