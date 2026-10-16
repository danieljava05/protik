# ProTik: Vision

## Thesis

ProTik is short-form video you control, built for expensive data
and African languages.

## Status

**Pre-alpha. Nothing is built yet.** This document describes what we
are building and how we will judge it. Claims about performance, cost
or accuracy will appear in the repo only once they are measured, with
the hardware and method stated.

## Who it's for

Nigerian viewers and creators on mobile data plans who want to choose
what they watch, know why they see it, and not burn their data budget
doing it.

## What we attack

Each problem below forces a specific design decision.

1. **Opaque ranking.** Every feed item carries structured score
   components, and users can pick a ranking mode and adjust weights.
   *Forces:* a pluggable `Ranker` interface, built before the feed.
2. **Data cost.** A per-user monthly data budget, a prefetch cap, and
   144p and audio-only renditions.
   *Forces:* extra renditions in the transcode pipeline, with measured
   storage and compute cost.
3. **Weak African-language support.** ASR captions and transcript
   search in Yoruba, Igbo, Hausa, and Nigerian Pidgin.
   *Forces:* captions as a first-class table and a job step in the
   existing queue, with published WER per language.

## What we ignore (for now)

Live streaming, creator payouts, duets/stitches, an in-app editor,
native mobile apps, and a recommender trained on user data.

## Safety and legal floor

A video platform with user uploads carries obligations from day one.
Before any public deployment, the following must exist and be tested:

- Content reporting and a takedown path, with every moderation action
  audit-logged (actor, time, reason).
- A documented procedure for child-safety reports and for copyright
  complaints.
- Compliance with the Nigeria Data Protection Act (NDPA): data
  minimisation, a privacy notice, and account deletion that actually
  deletes. Audit records are retained only as long as the law and
  safety require, and keep no personal content beyond what is needed.

This is an engineering project, not legal advice. Legal review is
required before a real launch.

## How we judge ourselves

- **MB consumed per 10 minutes of scrolling**, with and without data
  saver, using a fixed scripted session, device and network profile.
- **WER per language** on a labeled test set, including failure cases.
  We publish the numbers even when they are poor.
- **Feed p99 latency, transcode throughput, counter accuracy under
  load**, with hardware stated.

We do not claim "better than TikTok." We claim measured numbers.

## Known risks

- **ASR quality.** Open models are weak on these languages, and
  Pidgin has little labeled data. WER may be high. The deliverable is
  an honest measurement and a path to improvement.
- **Scope.** The three goals are large. Work proceeds one ticket at a
  time, to the Definition of Done in [CONTRIBUTING.md](CONTRIBUTING.md).
- **Infrastructure cost.** Extra renditions multiply storage and
  compute. We measure this before committing to defaults.

## Where to go next

- [README.md](README.md): overview and how to run (once there is
  something to run)
- [CONTRIBUTING.md](CONTRIBUTING.md): engineering rules and
  Definition of Done
- [docs/ROADMAP.md](docs/ROADMAP.md): ordered tickets and milestones
- [DESIGN.md](DESIGN.md): current architecture and its limits
- [docs/adr/](docs/adr/): architecture decision records
