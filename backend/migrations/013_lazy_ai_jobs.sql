-- Before lazy generation, the scheduler created one initial job for every event/language/method.
-- Those jobs are obsolete; persisted ai_analysis rows remain available. Administrator-approved
-- regenerations use target_revision > 1 and are intentionally retained.
DELETE FROM shared_ai_job WHERE target_revision <= 1;
