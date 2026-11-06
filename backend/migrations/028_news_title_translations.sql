-- Shared server-generated headlines; old rows are queued without touching their original text.
ALTER TABLE news_articles ADD COLUMN title_translations TEXT NOT NULL DEFAULT '{}';
ALTER TABLE news_articles ADD COLUMN title_translation_status TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE news_articles ADD COLUMN title_translation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE news_articles ADD COLUMN title_translation_attempted_at INTEGER;
ALTER TABLE news_articles ADD COLUMN title_translation_claim TEXT;
CREATE INDEX news_title_translation_queue ON news_articles(published_at DESC,id)
WHERE title_translation_status!='available' AND title_translation_attempts<3;
