/**
 * Minimal typed client for the ProTik API. The frontend is a test harness: it exists to
 * exercise the backend and make its behaviour (status, correlation ID, replay header,
 * problem documents) visible, not to be a product UI.
 */
export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** RFC 9457 problem document, as sent by the backend. */
export type Problem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
};

export type ApiResult<T> =
  | { ok: true; status: number; data: T; correlationId: string | null; replayed: boolean }
  | {
      ok: false;
      status: number; // 0 when the server could not be reached
      problem: Problem | null;
      correlationId: string | null;
      networkError?: string;
    };

export type Registered = { id: string; username: string };
export type Token = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
};
export type Me = { id: string; username: string; displayName: string };
export type Mode = { code: string; description: string; followedOnly: boolean; weights: Record<string, number> };
export type ComponentPreference = {
  code: string;
  description: string;
  modeWeight: number;
  override: number | null;
  effectiveWeight: number;
};
export type Preferences = { mode: string; followedOnly: boolean; components: ComponentPreference[] };

async function request<T>(path: string, init: RequestInit): Promise<ApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, init);
  } catch (error) {
    return {
      ok: false,
      status: 0,
      problem: null,
      correlationId: null,
      networkError: error instanceof Error ? error.message : "network error",
    };
  }
  const correlationId = response.headers.get("X-Correlation-Id");
  const text = await response.text();
  const body = text ? safeJson(text) : null;
  if (response.ok) {
    return {
      ok: true,
      status: response.status,
      data: body as T,
      correlationId,
      replayed: response.headers.get("Idempotent-Replayed") === "true",
    };
  }
  return { ok: false, status: response.status, problem: body as Problem | null, correlationId };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const json = { "Content-Type": "application/json" };

export function register(
  body: { username: string; email: string; password: string },
  idempotencyKey: string,
) {
  return request<Registered>("/v1/auth/register", {
    method: "POST",
    headers: { ...json, "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(body),
  });
}

export function login(body: { identifier: string; password: string }) {
  return request<Token>("/v1/auth/login", {
    method: "POST",
    headers: json,
    body: JSON.stringify(body),
  });
}

export function refresh(refreshToken: string) {
  return request<Token>("/v1/auth/refresh", {
    method: "POST",
    headers: json,
    body: JSON.stringify({ refreshToken }),
  });
}

export function logout(refreshToken: string) {
  return request<null>("/v1/auth/logout", {
    method: "POST",
    headers: json,
    body: JSON.stringify({ refreshToken }),
  });
}

export function logoutAll(accessToken: string) {
  return request<null>("/v1/auth/logout-all", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

const bearer = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` });

export function modes(accessToken: string) {
  return request<Mode[]>("/v1/feed/modes", { headers: bearer(accessToken) });
}

export function preferences(accessToken: string) {
  return request<Preferences>("/v1/feed/preferences", { headers: bearer(accessToken) });
}

export function savePreferences(accessToken: string, body: { mode: string; weights: Record<string, number> }) {
  return request<Preferences>("/v1/feed/preferences", {
    method: "PUT",
    headers: { ...json, ...bearer(accessToken) },
    body: JSON.stringify(body),
  });
}

export function me(accessToken: string) {
  return request<Me>("/v1/me", { headers: { Authorization: `Bearer ${accessToken}` } });
}
