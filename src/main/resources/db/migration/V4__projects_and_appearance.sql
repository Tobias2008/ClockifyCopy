CREATE TABLE project_settings (
 id NUMBER(1) PRIMARY KEY CHECK(id=1),
 project_name VARCHAR2(100 CHAR) DEFAULT 'Maturaball' NOT NULL,
 preset VARCHAR2(20) DEFAULT 'MATURABALL' NOT NULL CHECK(preset IN ('MATURABALL','JAHRESPROJEKT','VEREIN','FREIES_PROJEKT')),
 weekly_goal_minutes NUMBER(6) DEFAULT 300 NOT NULL CHECK(weekly_goal_minutes BETWEEN 0 AND 6000),
 version NUMBER(10) DEFAULT 0 NOT NULL
);
INSERT INTO project_settings(id) VALUES(1);
ALTER TABLE members ADD (
 color_preset VARCHAR2(10) DEFAULT 'PURPLE' NOT NULL CHECK(color_preset IN ('PURPLE','GREEN','BLUE','RED')),
 color_mode VARCHAR2(5) DEFAULT 'LIGHT' NOT NULL CHECK(color_mode IN ('LIGHT','DARK'))
);
ALTER TABLE time_entries MODIFY(team_id NULL);
ALTER TABLE time_entries ADD (approval_required NUMBER(1) DEFAULT 1 NOT NULL CHECK(approval_required IN (0,1)));
ALTER TABLE time_entries DROP CONSTRAINT entry_review_complete;
ALTER TABLE time_entries ADD CONSTRAINT entry_review_complete CHECK (
 (status IN ('APPROVED','DENIED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
 OR (status IN ('RUNNING','PENDING') AND decided_by IS NULL AND decided_at IS NULL AND reason IS NULL)
 OR (approval_required=0 AND status='APPROVED' AND decided_by IS NULL AND decided_at IS NULL AND reason IS NULL)
);
