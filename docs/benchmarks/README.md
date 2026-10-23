# Rendition cost benchmark (roadmap T3.2)

How much data does ten minutes of watching use at each quality level, and what does producing each level cost?

## Method

`RenditionBenchmark` (`src/test/java/com/protik/video/processing/`) encodes one input file at every rendition with
exactly the code and settings the upload pipeline uses (H.264 CRF 28 capped by the rendition's maximum bitrate, `veryfast`,
`+faststart`, two threads per encode), keeps the best of three runs for time, and prints a table. Reproduce it with:

```
PROTIK_BENCH_INPUT=/path/to/clip.mp4 PROTIK_BENCH_OUT=table.md ./mvnw -o test -Dtest=RenditionBenchmark
```

"MB per 10 min" is the file size scaled to ten minutes (MB = 10^6 bytes). It is what the viewer downloads if they watch
the whole thing once, without caching or retries and with no allowance for the player's own overhead.

## Result: Big Buck Bunny, 10 s, 1280x720 (animation, no audio track)

Clip: "Big Buck Bunny" (c) Blender Foundation, CC BY 3.0, as distributed by test-videos.co.uk (10 s, 720p, H.264).
Machine: AMD A8-7410 (AMD64 Family 22 Model 48), 4 logical processors, Windows 11, Java 25.0.3, ffmpeg 8.0.1.

| Rendition | Size | Average kbps | MB per 10 min | vs largest | Encode time (best of 3) | Speed |
|---|---:|---:|---:|---:|---:|---:|
| 144p | 0.07 MB | 57 | 4.3 | 7% | 2.77 s | 3.6x real time |
| 240p | 0.14 MB | 114 | 8.6 | 14% | 3.24 s | 3.1x real time |
| 480p | 0.49 MB | 392 | 29.4 | 49% | 5.48 s | 1.8x real time |
| 720p | 1.01 MB | 808 | 60.6 | 100% | 13.39 s | 0.7x real time |

Raw output: [renditions-bbb.md](renditions-bbb.md).

## What this says, and what it does not

- **Data saver on versus off.** With data saver off a viewer with good signal may fetch 720p, about 61 MB per ten minutes
  on this clip. With data saver on the policy serves 144p, about 4 MB, a reduction of roughly 93% for this clip. When the
  cap is exhausted the policy serves audio only, which this clip cannot measure because it has no audio.
- **Producing the ladder costs about 2.5 times the video's length on this machine** (2.77 + 3.24 + 5.48 + 13.39 s for a
  10 s clip), with 720p alone slower than real time. A three-minute upload therefore takes roughly seven minutes of one
  worker. That is a capacity-planning input, not a verdict: a modern server is several times faster than this CPU.
- **It is one clip.** Animation has large flat areas and compresses far better than camera footage with motion and noise,
  so real videos will be larger, probably by a factor of two or more at the same setting; the rendition caps (150, 300, 800,
  1800 kbps) bound the worst case, and `Rendition.estimateBytes` uses those caps. No claim about typical sizes is made here.
- **No audio, no talking head, no phone footage.** The audio-only rendition and speech-heavy content are unmeasured.
- **No storage cost in money.** Bytes are measured; prices are not.

## Still to do

Repeat on a set of clips that represent the platform (phone-shot speech, music video, screen recording) once such footage
with a suitable licence is available, and report the spread instead of one number.
