CREATE TABLE IF NOT EXISTS api_keys (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  key TEXT NOT NULL UNIQUE,
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  last_used INTEGER,
  usage_count INTEGER NOT NULL DEFAULT 0,
  token TEXT,
  token_expires_at INTEGER,
  reset_time INTEGER,
  chat_quota INTEGER,
  completions_quota INTEGER
);

CREATE INDEX IF NOT EXISTS idx_api_keys_default ON api_keys(is_default);
