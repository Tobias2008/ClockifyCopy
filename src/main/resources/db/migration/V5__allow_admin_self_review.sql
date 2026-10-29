-- Admins und Owner dürfen nun auch eigene Einträge prüfen.
-- Rollenprüfung, Versionierung, Entscheider, Zeitpunkt und Historie bleiben erhalten.
ALTER TABLE time_entries DROP CONSTRAINT no_self_review;
