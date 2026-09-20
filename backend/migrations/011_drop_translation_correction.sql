-- Manual translation corrections are gone: the translation pipeline now proofreads every
-- name with a second model call and retries the rejected ones, so a reader-submitted
-- correction queue has no place in the flow.
DROP TABLE IF EXISTS translation_correction;
DROP TABLE IF EXISTS translation_correction_mute;
