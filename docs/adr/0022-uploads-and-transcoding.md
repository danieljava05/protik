# ADR 0022: Uploads, malware scanning and transcoding

- Status: accepted
- Date: 2026-10-24

## Context

No real video exists yet: the feed runs on test rows. An upload is a file from a stranger, so it is hostile until proven
otherwise (the owner asked for "no malware to come through"). VISION.md wants each video available at several sizes so a person
on a small data plan can pick a small one. This environment has FFmpeg and PostgreSQL but **no MinIO/S3 and no ClamAV**, and
Docker is unavailable, so whatever depends on them can be built and unit-tested but not run against the real thing.

## Decisions

### 1. Storage is a port, and the first adapter is the local file system

`ObjectStorage` (put with a hard byte cap, open, size, delete, a local path for tools that need one) is an interface in the
video module. `FileSystemObjectStorage` implements it under `protik.storage.root`. Keys are validated against a whitelist
pattern and resolved paths are checked to stay under the root, so a key cannot climb out of it. **There is no S3/MinIO
adapter yet**: it cannot be tested here, and shipping an untested adapter would be a claim we cannot back. The port is
shaped so one can be added without touching the pipeline. The file-system adapter is for development and a single machine,
not for production scale.

### 2. Upload is two calls, streamed through the API

`POST /v1/videos` (with `Idempotency-Key`; 20 an hour per person) creates a video in `UPLOADING` with its description and
spoken language. `PUT /v1/videos/{id}/content` streams the file body, only for the creator and only while `UPLOADING`.
The server counts bytes while writing and **stops at `protik.upload.max-bytes` (default 200 MB)**, deleting what it wrote.
It reads the first bytes and checks the real container signature (MP4/MOV `ftyp`, WebM/Matroska EBML) against the declared
`Content-Type`; a mismatch or an unknown signature is 415. Nothing is trusted from the file name, because there is none.
The file lands under a `quarantine/` key, is recorded as the video's `ORIGINAL` asset (so deleting the video can never
orphan it, via the existing tombstone trigger), the video becomes `PROCESSING` and a `video.process` job is queued, all in one
transaction.

*Rejected: presigned direct-to-storage upload (the original plan).* It needs S3 and cannot be tested here, and it moves the size
cap and signature check out of our hands. It is the right design at scale and stays a future adapter-level change.
*Rejected: multipart form upload.* It buffers or spools to disk before the application sees a byte count.

### 3. Nothing is published until it passes four gates

The `video.process` job (at-least-once, so every step is idempotent) runs, in order:

1. **Malware scan** through a `MalwareScanner` port. `ClamdScanner` speaks the clamd `INSTREAM` protocol over TCP (no new
   dependency). It is tested against a fake clamd server for the protocol, the size limit and every failure mode; **it has not
   run against a real ClamAV**. `protik.scan.mode` is `clamd` by default, so with no scanner reachable the job fails and
   retries, and the video stays unpublished. `disabled` exists for development; it marks the video `SKIPPED` (never `CLEAN`),
   logs a warning on every use, and the README and DESIGN.md say never to use it in production. An infected file fails the
   video, deletes the file and records the reason.
2. **Probe** with `ffprobe` (no network protocols): there must be a video stream, a duration of 1 s to
   `protik.upload.max-duration` (default 3 minutes), dimensions at most 4096 and a bounded number of streams.
3. **Transcode** with `ffmpeg` to the renditions in `video.renditions` that are no larger than the source, plus audio only,
   as H.264/AAC progressive MP4 with `+faststart` (and Opus-free, widely playable AAC for audio), and a JPEG thumbnail.
4. **Publish**: assets rows, `PUBLISHED`, duration, and `VideoPublished` commit together; the original is deleted.

*Process isolation.* FFmpeg runs as a child process from a fixed argument list (no shell), in a private working directory,
with a clean environment, `-nostdin`, a protocol whitelist of `file`, a thread limit, an output size limit and a wall-clock
timeout after which it is killed. **This is not a sandbox**: a vulnerability in FFmpeg's demuxers would run with the
application's privileges. Production must run the worker in a container or user with no network, a read-only file system
except its work directory, and CPU/memory limits. That is deployment work not done here and is listed in DESIGN.md.

### 4. Progressive MP4 per rendition, not HLS (for now)

Each rendition is one MP4 file the client can fetch whole or by byte range. The client already receives a data policy that
names the rendition to use (ADR 0018), so choosing a size does not need adaptive streaming; it needs the sizes to exist and be
small. HLS would add segment and playlist management for adaptive switching mid-video, which matters more later. The schema's
asset `kind` gains `MP4_VARIANT`. *Rejected: HLS now.* More moving parts for a benefit (mid-playback switching) the first
users do not need.

### 5. Serving

`GET /v1/videos/{id}/renditions/{code}` streams a published video's file with HTTP Range support, `nosniff`, and long-lived
caching (files never change once published). It needs a signed-in user, like the rest of the API. Quarantined files are never
served. A CDN in front is a deployment concern.

### 6. Files are deleted with their rows

The existing tombstone trigger records every deleted asset key (including cascades, e.g. from account deletion, ADR 0020); a
scheduled sweeper deletes those objects and then the tombstone, and tolerates an already missing object.

## Consequences

- The video module gets its first real write path and a small configuration surface (`protik.storage.*`, `protik.upload.*`,
  `protik.scan.*`, `protik.ffmpeg.*`).
- The measured size of each rendition is stored, which T3.2 uses to report megabytes per ten minutes.
- Tests that run FFmpeg need it installed; CI installs it and fails (rather than skips) if it is missing.

## What this does not handle
Resumable uploads (a dropped connection restarts the file), S3/MinIO and CDN delivery, HLS/adaptive streaming, DRM, audio
loudness normalisation, stabilisation or content analysis, extraction of captions, a real sandbox for FFmpeg, per-person
storage quotas, deduplication, and re-processing an existing video when the ladder changes.
