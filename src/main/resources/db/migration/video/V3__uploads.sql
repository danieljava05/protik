-- Uploads and processing (ADR 0022).
-- Progressive MP4 per rendition instead of HLS for now (ADR 0022 decision 4).
ALTER TABLE video.video_assets DROP CONSTRAINT video_assets_kind_check;
ALTER TABLE video.video_assets ADD CONSTRAINT video_assets_kind_check
  CHECK (kind IN ('ORIGINAL','HLS_MASTER','HLS_VARIANT','MP4_VARIANT','THUMBNAIL'));
ALTER TABLE video.video_assets DROP CONSTRAINT video_assets_check;
ALTER TABLE video.video_assets ADD CONSTRAINT video_assets_check
  CHECK ((kind IN ('HLS_VARIANT','MP4_VARIANT')) = (rendition_code IS NOT NULL));

-- What the scan found, recorded honestly: SKIPPED means scanning was switched off, never "clean".
ALTER TABLE video.videos
  ADD COLUMN scan_status text NOT NULL DEFAULT 'PENDING'
    CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','SKIPPED')),
  ADD COLUMN failure_reason text CHECK (length(failure_reason) <= 200);
-- Videos that existed before scanning (demo rows) have nothing to scan.
UPDATE video.videos SET scan_status = 'SKIPPED';
