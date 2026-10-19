# ADR 0014: A pluggable, transparent Ranker

- Status: accepted
- Date: 2026-10-19

## Context

VISION.md, goal 1: "every feed item carries structured score components, and users
can pick a ranking mode and adjust weights", forcing "a pluggable `Ranker` interface,
built before the feed". The schema already defines components, modes, mode weights and
per-user weight overrides (ADR 0005). The question is the shape of the scoring itself.

## Options considered

### A. One opaque score computed in SQL (`ORDER BY` an expression)
Fast and simple, but the reasons behind a score exist only inside a query. Explaining
an item would mean a second, parallel implementation that can drift from the first.

### B. A learned model (a recommender trained on user data)
Out of scope for the vision ("What we ignore"), and the opposite of explainable.

### C. A weighted sum of normalised components, computed in Java
Each component is a small pure function returning a value in [0, 1]. The item's score
is the sum of `weight x value`. The explanation is the list of (component, value,
weight, contribution) that produced the score: it cannot disagree with the score because
it is how the score is computed.

## Decision

C.

- `Ranker` is the interface the feed depends on; `WeightedRanker` is the implementation.
- `ScoreFunction` is one component. New components are added by registering a bean; the
  ranker does not change. A component's code ties it to `feed.ranking_components`.
- **Values are in [0, 1], weights are in [0, 1]** (as the schema enforces), so the score is
  bounded by the sum of the weights and a user's weight change has an obvious meaning.
  `total = sum(weight x value)`, and the invariant "total equals the sum of the listed
  contributions" is tested on thousands of random inputs.
- The explanation is **structured data** (records), never a sentence. Clients build
  their own wording, in any language.
- Initial components, each a documented formula with configurable constants:

  | Code | Value | Notes |
  |---|---|---|
  | `RECENCY` | `0.5 ^ (age / half-life)` | half-life 24 h |
  | `ENGAGEMENT` | `r / (r + k)`, with `r` = (likes + 2 x comments + 0.1 x views) per hour of age (age at least 1 h) | saturating, `k` = 10 |
  | `FOLLOWING` | 1 if the viewer follows the creator, else 0 | |
  | `TOPIC` | share of the video's tags the viewer is interested in | 0 if the video has no tags |

- **Modes are data**, not code: a named set of weights (`feed.ranking_mode_weights`). A mode
  may also restrict candidates (`followed_only`, new column), because "only creators you
  follow" is a filter, not a weight. User overrides replace the mode's weight per component.
- **Deterministic order:** score, then newest first, then ID. With all weights zero every
  score is zero and the feed is simply newest first.
- **Scoring happens in Java over a bounded candidate pool** selected by the database; the
  candidate selection and paging come in T2.3. The ranker itself performs no I/O, so it is a
  pure function and tests need no database.

## Consequences

- Adding or changing a component is a code change plus a row in `ranking_components`.
- Because every component is computed at a fixed "as of" time, a page of results can be
  reproduced exactly later (needed for stable pagination in T2.3).
- A linear sum cannot express interactions ("only boost engagement for followed
  creators"). If that is wanted, it becomes a new component.
- The constants above are starting points, not tuned values: nothing here has been
  evaluated against real viewing behaviour, and no claim of feed quality is made.

## Why the alternatives were rejected

A hides the reasoning the vision promises to expose. B contradicts the vision's scope and its
transparency goal.

## What this does not handle
Personalisation from watch history, diversity or de-duplication of creators, negative signals
(skips, blocks), cold-start for new users, and any evaluation of whether the ranking is good.
