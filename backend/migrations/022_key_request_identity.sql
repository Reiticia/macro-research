-- Display-only Telegram identity snapshots; authorization continues to use numeric user IDs.
-- Existing requests remain valid and have no stored names until the user applies again.
ALTER TABLE api_key_request ADD COLUMN requester_username TEXT;
ALTER TABLE api_key_request ADD COLUMN requester_name TEXT;
