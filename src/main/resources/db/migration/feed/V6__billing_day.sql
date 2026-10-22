-- ADR 0018 decision 3: data plans renew on the day they were bought, not on the 1st.
-- The budget period runs from the most recent billing day to the next. 28 is the largest day every month has.

ALTER TABLE feed.user_feed_settings
  ADD COLUMN billing_day smallint NOT NULL DEFAULT 1 CHECK (billing_day BETWEEN 1 AND 28);
