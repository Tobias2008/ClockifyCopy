-- Zusätzliche fachliche Sicherungen gelten auch bei versehentlichen direkten SQL-Schreibzugriffen.
ALTER TABLE time_entries ADD CONSTRAINT entry_review_complete CHECK (
 (status IN ('APPROVED','DENIED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
 OR (status IN ('RUNNING','PENDING') AND decided_by IS NULL AND decided_at IS NULL AND reason IS NULL)
);
ALTER TABLE time_entries ADD CONSTRAINT entry_denial_reason CHECK (
 status <> 'DENIED' OR (reason IS NOT NULL AND LENGTH(TRIM(reason)) >= 3)
);
ALTER TABLE time_entries ADD CONSTRAINT entry_approved_no_reason CHECK (status <> 'APPROVED' OR reason IS NULL);
ALTER TABLE time_entries ADD CONSTRAINT entry_description CHECK (LENGTH(TRIM(description)) >= 3);
ALTER TABLE time_entries ADD CONSTRAINT entry_positive_start CHECK (started_at >= 0);
ALTER TABLE members ADD CONSTRAINT member_normalized_email CHECK (email = LOWER(TRIM(email)));
ALTER TABLE account_tokens ADD CONSTRAINT token_normalized_email CHECK (email = LOWER(TRIM(email)));
ALTER TABLE activities ADD CONSTRAINT activity_color CHECK (REGEXP_LIKE(color, '^#[0-9A-Fa-f]{6}$'));
