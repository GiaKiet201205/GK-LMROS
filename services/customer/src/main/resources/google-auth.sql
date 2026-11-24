ALTER TABLE customers ADD COLUMN google_sub text UNIQUE,ADD COLUMN google_email text;
ALTER TABLE customers ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE customers ADD CONSTRAINT customer_has_login CHECK(password_hash IS NOT NULL OR google_sub IS NOT NULL);
CREATE TABLE google_login_challenges(nonce_hash text PRIMARY KEY,expires_at timestamptz NOT NULL);
CREATE INDEX google_challenge_expiry ON google_login_challenges(expires_at);
