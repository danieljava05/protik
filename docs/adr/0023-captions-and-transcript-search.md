# ADR 0023: Captions, machine transcription and transcript search

- Status: accepted
- Date: 2026-10-24

## Context

VISION.md makes captions in Nigerian languages a goal, and ADR 0004 says no language is offered machine transcription until its
word error rate has been measured by a fixed protocol. Nothing in this environment can measure that: there is no speech model
installed, no test set downloaded and licence-checked, and no reliable network to fetch either. The schema already holds
`captions` and `caption_segments` with accent-insensitive search indexes.

## Decisions

### 1. Creator captions come first and are fully built

A creator can upload their own WebVTT for their video (`PUT /v1/videos/{id}/captions/{language}`, UTF-8, at most 1 MB and 5,000
cues). The parser is strict where it matters (header, timing, limits, cues starting no later than one second after the video
ends) and lenient where a transcript does not need more (cue settings, notes, styles, regions are ignored). Text is stored as
plain text with inline tags removed, and written back escaped, so a caption cannot carry markup. Uploading again replaces the
captions of that language. Anyone can read published captions as `text/vtt`; the owner can read them earlier.

Captions are first-class and exist before any machine model does, so a creator who speaks Yoruba or Hausa can caption their own
video today.

### 2. A machine caption never replaces a creator's

Captions are stored per video, language and source (`CREATOR` or `ASR`). Reading prefers the creator's. Each machine caption
records the model name and version and its average confidence, so scores can be compared across models later.

### 3. Machine transcription is a port that is off until a language has a measured error rate

`Transcriber` is an interface; **no model ships** and the default implementation reports itself unavailable. After a video is
published, a `video.caption` job is queued only if its spoken language has `asr_supported = true`, and the job again does
nothing if the flag is off or no transcriber is configured. The flag is set to true only by a migration or an operator after the
language's WER has been measured with the tooling below and published. Today it is false for every language, so **no machine
caption is ever produced**; the wiring is tested with a fake transcriber that returns fixed text.

*Rejected: bundling a model and a default "supported" list now.* It would make a quality claim we cannot back, which is the exact
thing ADR 0004 forbids.

### 4. Search is accent-insensitive on purpose

`GET /v1/search/transcripts?q=&language=&limit=` matches the stored text through the same `unaccent` and `simple` text-search
configuration as the index, so people who type Yoruba without tone marks or dots ("omo") find "ọmọ". It returns only published
videos, ranks by `ts_rank`, returns at most 50 matches and has no paging cursor yet. A query is 2 to 100 characters with no control
characters (a NUL used to reach the database and produce a 500; found by a test, now refused with 400), is bound as a parameter
and turned into a query by `plainto_tsquery`, so it cannot inject syntax. Sixty searches a minute per person.

*Rejected: trigram fuzzy matching in the first version.* The trigram index exists, but fuzzy results need a relevance cut-off that
should be tuned on real transcripts, which do not exist yet. It stays a later improvement.

### 5. The evaluation tool is built; its inputs are not

`Evaluation` implements the protocol of ADR 0004: one normaliser (case, punctuation, apostrophes inside words; digits left as
written), word and character error rates by edit distance, **both diacritic policies in every report**, per-language results
with utterance and word counts, a 95% bootstrap interval over utterances (1,000 resamples, fixed seed, so a report is
reproducible), and a failure gallery of the worst utterances. Input is a TSV of `id, language, reference, hypothesis`. The tool
is tested on hand-computed cases.

**What is not done: no test set has been chosen or licence-checked, no model has been run, and so there is no baseline WER for any
language.** The roadmap says so.

### 6. Caption files are tidied like any other stored file

A database trigger records the file key of a deleted caption (including by cascade) in the existing tombstone table, so deleting a
video or an account removes its caption files (ADR 0020, ADR 0022).

## Consequences

- Search works on creator captions as soon as they exist.
- Turning machine captions on is a deliberate act with a published number behind it.

## What this does not handle
Any machine transcription, translation, word-level timestamps, speaker labels, caption editing in place, SRT or other formats,
live captions, search ranking tuned on real data, fuzzy or prefix matching, paging of search results, and the language-specific
issues of Nigerian Pidgin spelling variation.
