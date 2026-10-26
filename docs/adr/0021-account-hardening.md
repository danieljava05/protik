# ADR 0021: Password reset, email verification and common-password check

- Status: accepted
- Date: 2026-10-23

## Context

DESIGN.md says the service must not be deployed publicly before account hardening. Today a person who forgets their password
loses the account, nobody can tell whether an email address is really theirs, and `password123456` is accepted. Sending
email needs a provider, an account and a sending domain, none of which exist yet.

## Decisions

### 1. One token mechanism for both flows

A new `identity.email_tokens` table holds single-use tokens for two purposes, `VERIFY_EMAIL` (24 hours) and `RESET_PASSWORD`
(30 minutes). The token is 32 random bytes from `SecureRandom`, shown once and stored only as a SHA-256 hash (the same rule as
refresh tokens, ADR 0012). Asking for a new token of a purpose invalidates the older unused ones. Using a token marks it used in
the same transaction as its effect, under a row lock, so two simultaneous uses cannot both succeed. Expired and used tokens are
purged after a week.

*Rejected: signed, self-contained tokens (JWT).* They cannot be made single-use or revoked without a table anyway.
*Rejected: a six-digit code.* One million possibilities is guessable within the rate limits we could afford; 256 bits is not.

### 2. Sending goes through a port

`EmailSender` is an interface in `identity`. The only implementation today, `LoggingEmailSender`, **delivers nothing**: it logs
that a message for a purpose was created, and logs the link containing the token only when `protik.mail.log-links=true` (for
local development). A real provider (SMTP or an API) is a later, separate decision and needs a verified sending domain, SPF and
DKIM. Until then the flows work end to end in tests and locally, and **nobody receives an email in production**; this is
stated in DESIGN.md. Mail is sent after the database transaction commits, so a rolled-back request sends nothing and a failed
send never undoes an account; the person can ask again.

The link goes to a configured front-end base URL (`protik.mail.link-base`), never to the request's `Host` header, which a
caller controls.

### 3. Password reset

`POST /v1/auth/password-reset` takes an email and **always answers 202**, whether or not an account has that address, so it cannot
be used to find out who is registered. It is limited to 3 requests an hour per address and 20 an hour per client address.
`POST /v1/auth/password-reset/confirm` takes the token and a new password, checks the password policy, replaces the hash, marks the
address verified (the person just proved they control it), and **ends every session of the account** (reason `PASSWORD_RESET`), so
a thief who had a session loses it. Suspended and pending-deletion accounts cannot reset. Wrong or expired tokens all answer the
same 400.

### 4. Email verification

At registration, and on `POST /v1/auth/verify-email/request` (signed in, 3 an hour), a token is sent; `POST /v1/auth/verify-email`
with the token marks the address verified. Verification gates nothing yet. The uploads ticket should require it before a person
can publish, which is the real reason to verify.

### 5. Common passwords

The password policy (ADR 0011) also refuses any password on a bundled list of 9,126 common passwords of at least 10 characters,
compared case-insensitively (source and licence in NOTICE). The list is offline on purpose: asking an external service about
each password, even with k-anonymity, adds a runtime dependency and a privacy question for little gain at this stage. A list this
size catches the worst choices only. It is not a breach database, and the README and DESIGN.md must not call it one.

## Consequences

- Registration now sends a message, so tests use a capturing sender.
- A reset ends the person's sessions on every device, which is intended.
- Until a real provider exists, password reset cannot be used by real people.

## What this does not handle
Multi-factor authentication, changing a password or email while signed in, notifying the person that their password changed,
resetting by phone number, delivery bounces and retries, a large breach corpus, and rate limits per account beyond those above.

## Addendum: SMTP delivery (2026-10-26)

`protik.mail.sender=smtp` sends through any SMTP server configured with Spring's `spring.mail.*` (host, port, credentials, TLS).
`protik.mail.from` is required, and `protik.mail.link-base` must be `https` (plain `http` only for `localhost`), because the link
is a credential. The default stays `log`, which delivers nothing. With `smtp` selected and no mail server configured, startup
fails rather than silently dropping mail. The recipient must be one plain address (no whitespace, comma, semicolon, angle
bracket or quote), so a header-injection attempt fails before any message is built. Mail is plain text, UTF-8, marked
`Auto-Submitted`. A failed send is logged by exception class only (never the address) and never undoes the request.

Two dependencies were added, as AGENTS.md requires written justification: `spring-boot-starter-mail` (Spring's own JavaMail
integration, instead of a hand-written SMTP client) and `greenmail-junit5` (test scope: a real SMTP server inside the test
JVM, so the sender is tested against the actual protocol and not a mock).

**Verified:** against GreenMail, including the whole path (register, receive, open the link). **Not verified:** any real
provider, TLS negotiation, a sending domain (SPF, DKIM, DMARC) or deliverability. Those need an account and a domain.
