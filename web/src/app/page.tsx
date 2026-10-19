"use client";

import { useState } from "react";
import * as api from "@/lib/api";
import RankingSettings from "./RankingSettings";

type Exchange = {
  label: string;
  at: string;
  result: api.ApiResult<unknown>;
};

export default function Home() {
  // The access token lives in memory only: it is gone on reload, which is the safest place for a
  // harness (nothing for an XSS payload to read from storage). Refresh tokens (T1.2) will change this.
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState<string | null>(null);
  // The refresh token that was just replaced, kept so you can present it again and watch the
  // server treat it as reuse (ADR 0012).
  const [staleRefreshToken, setStaleRefreshToken] = useState<string | null>(null);
  const [profile, setProfile] = useState<api.Me | null>(null);
  const [log, setLog] = useState<Exchange[]>([]);
  const [busy, setBusy] = useState(false);

  const [reg, setReg] = useState({ username: "", email: "", password: "" });
  // One key per intended registration, created on first submit (never during render, which would
  // differ between server and browser). Retrying with the same key must replay, not duplicate.
  const [regKey, setRegKey] = useState<string | null>(null);
  const [cred, setCred] = useState({ identifier: "", password: "" });

  function record(label: string, result: api.ApiResult<unknown>) {
    setLog((previous) =>
      [{ label, at: new Date().toLocaleTimeString(), result }, ...previous].slice(0, 12),
    );
  }

  async function run<T>(label: string, action: () => Promise<api.ApiResult<T>>) {
    setBusy(true);
    try {
      const result = await action();
      record(label, result);
      return result;
    } finally {
      setBusy(false);
    }
  }

  async function onRegister(event: React.FormEvent) {
    event.preventDefault();
    const key = regKey ?? crypto.randomUUID();
    setRegKey(key);
    const result = await run("POST /v1/auth/register", () => api.register(reg, key));
    if (result.ok) {
      setCred({ identifier: reg.username, password: reg.password });
      setRegKey(null); // success: the next registration is a new request
    }
  }

  async function onLogin(event: React.FormEvent) {
    event.preventDefault();
    const result = await run("POST /v1/auth/login", () => api.login(cred));
    if (result.ok) {
      setAccessToken(result.data.accessToken);
      setRefreshToken(result.data.refreshToken);
      setStaleRefreshToken(null);
      const profileResult = await run("GET /v1/me", () => api.me(result.data.accessToken));
      setProfile(profileResult.ok ? profileResult.data : null);
    }
  }

  async function onMe() {
    if (!accessToken) return;
    const result = await run("GET /v1/me", () => api.me(accessToken));
    setProfile(result.ok ? result.data : null);
  }

  async function onRefresh() {
    if (!refreshToken) return;
    const result = await run("POST /v1/auth/refresh", () => api.refresh(refreshToken));
    if (result.ok) {
      setStaleRefreshToken(refreshToken);
      setAccessToken(result.data.accessToken);
      setRefreshToken(result.data.refreshToken);
    } else if (result.status === 401) {
      forget(); // the session is over (revoked, expired or reused): log in again
    }
  }

  async function onReplayOld() {
    if (!staleRefreshToken) return;
    const result = await run("POST /v1/auth/refresh (old token)", () => api.refresh(staleRefreshToken));
    if (!result.ok && result.status === 401) {
      forget();
    }
  }

  async function onLogout() {
    if (refreshToken) {
      await run("POST /v1/auth/logout", () => api.logout(refreshToken));
    }
    forget();
  }

  async function onLogoutAll() {
    if (!accessToken) return;
    await run("POST /v1/auth/logout-all", () => api.logoutAll(accessToken));
    forget();
  }

  function forget() {
    setAccessToken(null);
    setRefreshToken(null);
    setStaleRefreshToken(null);
    setProfile(null);
  }

  return (
    <main>
      <header>
        <h1>ProTik</h1>
        <p className="tagline">
          Backend test harness. API: <code>{api.API_URL}</code>
        </p>
        <p className={accessToken ? "status ok" : "status"}>
          {profile ? `Signed in as ${profile.username}` : "Not signed in"}
        </p>
      </header>

      <div className="grid">
        <form onSubmit={onRegister}>
          <h2>Register</h2>
          <label>
            Username
            <input
              value={reg.username}
              onChange={(e) => setReg({ ...reg, username: e.target.value })}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Email
            <input
              type="email"
              value={reg.email}
              onChange={(e) => setReg({ ...reg, email: e.target.value })}
              autoComplete="email"
              required
            />
          </label>
          <label>
            Password (10+ characters)
            <input
              type="password"
              value={reg.password}
              onChange={(e) => setReg({ ...reg, password: e.target.value })}
              autoComplete="new-password"
              required
            />
          </label>
          <p className="hint">
            Idempotency-Key: <code>{regKey ? `${regKey.slice(0, 8)}…` : "created on submit"}</code> Submit twice without changing anything
            to see a replay (<code>Idempotent-Replayed: true</code>) instead of a duplicate.
          </p>
          <button disabled={busy}>Create account</button>
        </form>

        <form onSubmit={onLogin}>
          <h2>Log in</h2>
          <label>
            Username or email
            <input
              value={cred.identifier}
              onChange={(e) => setCred({ ...cred, identifier: e.target.value })}
              autoComplete="username"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={cred.password}
              onChange={(e) => setCred({ ...cred, password: e.target.value })}
              autoComplete="current-password"
              required
            />
          </label>
          <div className="row">
            <button disabled={busy}>Log in</button>
            <button type="button" className="secondary" disabled={busy || !accessToken} onClick={onMe}>
              Call /v1/me
            </button>
            <button type="button" className="secondary" disabled={busy || !refreshToken} onClick={onRefresh}>
              Refresh
            </button>
            <button type="button" className="secondary" disabled={busy || !staleRefreshToken} onClick={onReplayOld}>
              Replay old refresh token
            </button>
            <button type="button" className="secondary" disabled={busy || !accessToken} onClick={onLogout}>
              Log out
            </button>
            <button type="button" className="secondary" disabled={busy || !accessToken} onClick={onLogoutAll}>
              Log out everywhere
            </button>
          </div>
          <p className="hint">
            Refresh rotates the token. &quot;Replay old refresh token&quot; presents the one just replaced: within 10
            seconds it is refused, later it revokes the whole session (token theft protection).
          </p>
          {profile && (
            <pre className="profile">{JSON.stringify(profile, null, 2)}</pre>
          )}
        </form>
      </div>

      {accessToken && <RankingSettings accessToken={accessToken} onResult={record} />}

      <section>
        <h2>Requests</h2>
        {log.length === 0 && <p className="hint">Nothing yet. Every call shows up here.</p>}
        <ul className="log">
          {log.map((entry, index) => (
            <li key={index} className={entry.result.ok ? "ok" : "bad"}>
              <div className="line">
                <strong>{entry.label}</strong>
                <span>
                  {entry.result.status === 0 ? "no response" : entry.result.status} · {entry.at}
                </span>
              </div>
              <div className="meta">
                correlation ID: <code>{entry.result.correlationId ?? "none"}</code>
                {entry.result.ok && entry.result.replayed && <b> · replayed from an earlier request</b>}
              </div>
              <pre>{describe(entry.result)}</pre>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

function describe(result: api.ApiResult<unknown>): string {
  if (result.ok) {
    // Never show the full token: it is a credential.
    const data = result.data as Record<string, unknown> | null;
    if (data && typeof data.accessToken === "string") {
      const hidden = { ...data, accessToken: `${data.accessToken.slice(0, 16)}… (hidden)` } as Record<string, unknown>;
      if (typeof hidden.refreshToken === "string") {
        hidden.refreshToken = `${hidden.refreshToken.slice(0, 14)}… (hidden)`;
      }
      return JSON.stringify(hidden, null, 2);
    }
    return data === null ? "(no content)" : JSON.stringify(data, null, 2);
  }
  if (result.networkError) {
    return `Could not reach the API: ${result.networkError}`;
  }
  return JSON.stringify(result.problem, null, 2);
}
