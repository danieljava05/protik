# Privacy notice (DRAFT: not reviewed by a lawyer)

> Draft for counsel. The statements about what data exists and what happens to it are written from the code and are accurate
> as of this repository's state; the legal framing (lawful basis, regulator, transfers, minimum age) is **not** settled.

**Controller:** [OPERATOR: legal name, address, contact email]. **Data protection contact:** [OPERATOR].

## What we hold about you, and why

| What | Why | Kept until |
|---|---|---|
| Username, email address, password (stored only as an Argon2id hash, never readable) | To let you sign in and to contact you about your account | You delete your account |
| Display name, bio, preferred language | To show your profile | You delete your account |
| Sign-in sessions (a hash of each refresh token, creation and revocation times) | To keep you signed in and to sign you out everywhere | Until the session ends, plus a short clean-up period |
| Your videos, their descriptions, spoken language and hashtags; the captions you add | To show your videos and let people find them | You delete them, or your account |
| Your comments, follows, blocks and likes | To provide those features | You delete them, or your account |
| What you watched (views) and a short list of videos already shown to you | To count views and to avoid repeating videos | You delete your account |
| Your feed settings (ranking mode and weights), data-saver settings, monthly data cap, billing day | To personalise the feed and to help you spend less data | You delete your account |
| Data usage your app reports (bytes per video and quality) | To tell you how much of your data cap you have used | 400 days, then removed automatically, or when you delete your account |
| Reports you file about content or people (reason, your words) | To let moderators act | The report outlives your account but your identity and words are removed when you delete it |
| A moderation log of actions taken by moderators (who acted, on what, why) | Accountability. It contains pseudonymous IDs only | Not deleted with an account: it holds no name, email or content |
| Technical records: IP address handling is limited to the network address of the connection, which is **hashed** for rate limiting; request logs carry a correlation ID | Security and abuse prevention | Rate-limit counters expire on their own; [OPERATOR: log retention] |
| Email-verification and password-reset tokens (stored only as a hash) | To verify your address and to reset your password | 24 hours / 30 minutes, then purged within a week |

**We do not**: sell data, show third-party advertising, use your data to train machine-learning models, or send data to other
companies, except the processors in the next section. [OPERATOR: confirm before launch.]

## Processors and where data goes

[OPERATOR: hosting provider and region; email provider; any CDN; ClamAV runs on our own infrastructure.]
[COUNSEL: whether storing data outside Nigeria needs a transfer mechanism or a notice.]

## Your rights

You can ask us to: see your data, correct it, delete it, restrict or object to its use, and receive a copy.
[COUNSEL: the exact rights, response times and complaint route under the Nigeria Data Protection Act 2023 and the regulator,
the Nigeria Data Protection Commission.]

- **Deletion.** `POST /v1/me/deletion` deletes your account and your data in every part of the service. It happens within moments;
  your account stops working at once. **It does not reach backups or logs outside the database, caches on other people's devices,
  or anything someone has already downloaded.** [OPERATOR: backup retention period, which must be stated here.]
- **Access and export.** **Not built yet.** Until it is, requests are handled by hand by the operator. [OPERATOR]
- **Correction.** Profile editing through the API is **not built yet**.

## Children

[COUNSEL: the minimum age, whether parental consent is required for under-18s, and what the service must do for children.]
The software does not currently check anyone's age.

## Security

Passwords are hashed with Argon2id; refresh tokens and email tokens are stored only as hashes; uploads are scanned for malware
and re-encoded before anyone can watch them; access tokens last 15 minutes. **An access token issued before you deleted your
account keeps working for up to 15 minutes.** Email addresses are visible to the operator.
[OPERATOR: breach notification procedure; COUNSEL: the legal deadline to notify the regulator and affected people.]

## Machine captions

If machine captions are ever switched on for a language, the audio of videos in that language will be processed by a speech
model run by [OPERATOR: where]. Today **no machine captions are produced**.

## Changes and contact

[OPERATOR: how users are told of changes; contact details.]

---

### Open questions collected

- **[COUNSEL]** lawful basis for each purpose; minimum age and parental consent; cross-border transfer; retention periods;
  breach notification; regulator registration; whether a data protection impact assessment is required.
- **[OPERATOR]** controller identity; processors; backup and log retention; contact details; whether any analytics will be added
  (none exist today).
