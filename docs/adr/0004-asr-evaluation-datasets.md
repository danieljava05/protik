# ADR 0004: How ASR quality is measured

- Status: proposed (dataset licences and Pidgin source must be verified)
- Date: 2026-10-17

## Context

VISION.md promises published WER per language for Yoruba, Igbo, Hausa
and Nigerian Pidgin, failure cases included. A WER number is only
meaningful with a defined test set, text normalisation and decoding
setup. Public model cards report WER on very small subsets (for
example 50 utterances per language) without text normalisation, so
their figures are not comparable with each other or with ours.

## Candidate test data (to verify before use)

- **NaijaVoices**: large Igbo, Hausa and Yoruba speech corpus
  ([paper](https://arxiv.org/abs/2505.20564)).
- **FLEURS**: small, read speech, covers Yoruba, Igbo and Hausa.
- **Common Voice**: community read speech; coverage varies by language.
- **Nigerian Pidgin**: no confirmed standard benchmark identified.
  Likely requires a small test set we label ourselves, with speaker
  consent and a published licence.
- **Domain gap:** these are mostly read speech. Short-form video is
  spontaneous, noisy, code-switched speech. We will add a small
  hand-labeled sample of that kind and report it separately.

## Decision

1. Fix a **protocol** before any model is chosen: held-out test sets
   that are never used for training or tuning; documented text
   normalisation (case, punctuation, diacritics policy); WER and CER
   computed by one scripted tool; decoding settings recorded.
2. Report **per language, per test set**, with sample counts and
   confidence intervals, plus a failure-case gallery.
3. Report **diacritics handling explicitly** for Yoruba (tone marks
   change meaning); score both with and without.
4. Start with an off-the-shelf open model as a **baseline** and measure
   it ourselves. Fine-tuning is a later, separate ADR.
5. Verify every dataset licence permits our use before it enters the
   repo or a published result.

## Consequences

Numbers may be poor, especially for Pidgin and Igbo. They are
published anyway. The protocol, not the model, is the durable asset.

## Why the alternatives were rejected

Quoting model-card numbers would be borrowing someone else's
measurement on unlike data. Using only read-speech sets would hide the
real-world gap on video audio.
