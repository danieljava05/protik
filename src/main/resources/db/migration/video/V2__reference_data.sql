-- Reference data. asr_supported flips to true only after WER is measured (ADR 0004).

INSERT INTO video.languages (code, name, asr_supported) VALUES
  ('en','English',false),('yo','Yoruba',false),('ig','Igbo',false),
  ('ha','Hausa',false),('pcm','Nigerian Pidgin',false);   -- flipped to true only after WER is measured (ADR 0004)

INSERT INTO video.renditions (code, height_px, max_bitrate_kbps) VALUES
  ('audio',NULL,48),('144p',144,150),('240p',240,300),('480p',480,800),('720p',720,1800);
