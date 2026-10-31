-- Feature-use tallies from the Android app (Settings > Send feature-use tallies).
-- Totals per UTC day and feature only: no install id, no address, no per-action rows.
CREATE TABLE IF NOT EXISTS usage_counts (
  day INTEGER NOT NULL,
  app_version INTEGER NOT NULL,
  feature TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (day, app_version, feature)
);
-- One-off batch nonces, so a batch the app retries after a lost reply isn't counted twice. Removed after a week.
CREATE TABLE IF NOT EXISTS usage_batches (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_batches_time ON usage_batches(created_at);
-- Abuse counter: a secret-keyed, day-scoped hash of the network address. Removed after a day.
CREATE TABLE IF NOT EXISTS usage_rate_limits (
  id TEXT PRIMARY KEY,
  ip_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_rate_ip ON usage_rate_limits(ip_hash,created_at);
