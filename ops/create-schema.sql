-- In der richtigen PDB als DBA mit SQL*Plus/SQLcl ausführen, nicht im CDB-Root.
-- Tabellenraum USERS ggf. anpassen. Passwörter werden verdeckt abgefragt.
SET VERIFY OFF
ACCEPT schema_password CHAR PROMPT 'Neues Passwort für Schema ballzeit: ' HIDE
CREATE USER ballzeit IDENTIFIED BY "&schema_password" DEFAULT TABLESPACE USERS QUOTA 100M ON USERS;
GRANT CREATE SESSION, CREATE TABLE TO ballzeit;
UNDEFINE schema_password
-- Keine DBA-, RESOURCE-, UNLIMITED TABLESPACE- oder ANY-Rechte vergeben.
