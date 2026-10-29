-- Neue Konten starten ohne Stammteam; Zuweisung erfolgt später durch Admin/Owner.
ALTER TABLE members MODIFY (team_id NULL);
ALTER TABLE members ADD (school_code VARCHAR2(32 CHAR));
ALTER TABLE members ADD CONSTRAINT member_school_code_unique UNIQUE (school_code);
ALTER TABLE members ADD CONSTRAINT member_school_code_format CHECK (
 school_code IS NULL OR (school_code=LOWER(TRIM(school_code)) AND REGEXP_LIKE(school_code,'^[a-z0-9][a-z0-9._-]{1,31}$'))
);
-- NULL ist nur für bereits bestehende Konten erlaubt. Neue Konten validiert die API verpflichtend.
