CREATE TABLE IF NOT EXISTS download_requests (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL COLLATE NOCASE UNIQUE,
  token_hash TEXT NOT NULL UNIQUE,
  unsubscribe_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  confirmed_at INTEGER,
  token_used INTEGER NOT NULL DEFAULT 0,
  pending_updates INTEGER NOT NULL DEFAULT 0,
  consented_at INTEGER,
  wants_updates INTEGER NOT NULL DEFAULT 0 CHECK(wants_updates IN (0,1)),
  consent_version TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS signup_rate_limits (
  id TEXT PRIMARY KEY,
  ip_hash TEXT NOT NULL,
  email_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS signup_rate_time ON signup_rate_limits(created_at);
CREATE INDEX IF NOT EXISTS signup_rate_email ON signup_rate_limits(email_hash,created_at);
CREATE INDEX IF NOT EXISTS signup_rate_ip ON signup_rate_limits(ip_hash,created_at);
