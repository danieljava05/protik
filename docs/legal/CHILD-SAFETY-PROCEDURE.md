# Child-safety procedure (DRAFT: not reviewed by a lawyer)

> A technical queue is not a legal procedure. This document says what the software does when someone reports child-sexual-abuse
> or other child-safety content, what a human moderator must then do, and what remains undecided. **Reporting duties,
> authorities and evidence rules must be set by counsel before launch.** Until then, **do not operate the service for the public.**

## What the software does today

1. Any signed-in person can file a report with the reason `CHILD_SAFETY` (`POST /v1/reports`), against a video, a comment or an account.
2. The report goes to the front of the moderator queue (`GET /v1/moderation/reports`): child-safety reports first, then oldest first.
3. A moderator can **take down** the video (it leaves the feed and search at once and cannot be fetched), **hide** a comment, or
   **suspend** the account (its sessions end). A written reason is required and an append-only audit row records who did what and when.
4. A takedown **keeps** the stored files and the record (the video is marked `TAKEN_DOWN`, not deleted), so evidence is not destroyed by the takedown.
5. Uploads are scanned for malware and re-encoded, but **nothing in the software detects child-abuse imagery**. There is no hash
   matching and no classifier.

## What a human moderator must do

1. Open the queue at least [OPERATOR: every N hours; **target time from report to action to be set**].
2. For a `CHILD_SAFETY` report: **do not download, copy, forward or screenshot the material.** View it only as far as needed to decide.
3. If it appears to depict a child in a sexual way: take the video down immediately, suspend the uploader, and record the reason
   using neutral words (no description of the content).
4. Escalate to [OPERATOR: named responsible person] **the same day**, who decides, with counsel, what must be reported to which
   authority [COUNSEL: authority, channel and deadline; see below].
5. Do **not** delete the account or the files after a takedown; see "Gaps".

## Gaps (what the software cannot do yet)

- **No legal hold.** If the uploader deletes their own account (`POST /v1/me/deletion`), their videos, files and reports are
  deleted for real. That would destroy evidence a lawyer may require us to keep. A hold that blocks deletion of an account or
  video under investigation is **not built**; it needs a decision from counsel on what to keep, for how long, and where.
- **No evidence export** to an authority.
- **No automatic detection** (hash lists or classifiers) and no proactive scanning.
- **Moderators are people with database-granted roles**; there is no training, no welfare support and no second-person check for severe actions.
- **No notification** to the reporter or to the reported person, and no appeal.
- **Access tokens** live 15 minutes, so a suspended account can keep using its current token for up to 15 minutes.

## Questions for counsel [COUNSEL]

1. Which authority must be told about suspected child sexual abuse material found on the service, how, and by when?
2. What must be preserved (the video, the account data, logs), for how long, and under what conditions?
3. May the operator report to foreign bodies (for example the NCMEC CyberTipline or the IWF) as well, and is it required?
4. What are moderators allowed to view and keep, and what protections do they need?
5. What are the operator's duties under Nigerian law as an intermediary, and the safe-harbour conditions?
6. Does the age of the uploader or of those depicted change any step?

## Decisions for the operator [OPERATOR]

Named responsible person and deputy; moderation hours and target response time; how moderators are vetted and supported; where
evidence is stored if counsel requires it; who may be granted the moderator role.
