-- Agent-activity email alerts (Android Settings > Email alerts). One row per app install.
CREATE TABLE IF NOT EXISTS alert_subscriptions (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL COLLATE NOCASE,
  install_hash TEXT NOT NULL UNIQUE,
  confirm_hash TEXT UNIQUE,
  unsubscribe_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  confirm_expires_at INTEGER NOT NULL,
  confirmed_at INTEGER,
  sent_day INTEGER NOT NULL DEFAULT 0,
  sent_count INTEGER NOT NULL DEFAULT 0,
  last_key TEXT
);
CREATE TABLE IF NOT EXISTS alert_rate_limits (
  id TEXT PRIMARY KEY,
  ip_hash TEXT NOT NULL,
  email_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS alert_rate_time ON alert_rate_limits(created_at);
CREATE INDEX IF NOT EXISTS alert_rate_email ON alert_rate_limits(email_hash,created_at);
CREATE INDEX IF NOT EXISTS alert_rate_ip ON alert_rate_limits(ip_hash,created_at);
