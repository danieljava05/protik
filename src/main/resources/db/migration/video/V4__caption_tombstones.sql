-- A caption's rendered WebVTT file is an object in storage, so deleting a caption (including by cascade when its
-- video or account is deleted) must leave a tombstone for the sweeper, exactly as assets do (ADR 0022, ADR 0023).
CREATE FUNCTION video.record_caption_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.vtt_storage_key IS NOT NULL THEN
    INSERT INTO video.storage_tombstones (storage_key) VALUES (OLD.vtt_storage_key) ON CONFLICT DO NOTHING;
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER captions_tombstone AFTER DELETE ON video.captions
  FOR EACH ROW EXECUTE FUNCTION video.record_caption_tombstone();
