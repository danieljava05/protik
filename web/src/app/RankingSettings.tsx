"use client";

import { useEffect, useState } from "react";
import * as api from "@/lib/api";

type Props = {
  accessToken: string;
  onResult: (label: string, result: api.ApiResult<unknown>) => void;
  onSaved: () => void;
};

/**
 * Ranking transparency and control (VISION.md goal 1): see exactly which weights each mode applies,
 * pick one, and adjust any weight. What is saved is what the feed's ranker will use.
 */
export default function RankingSettings({ accessToken, onResult, onSaved }: Props) {
  const [modes, setModes] = useState<api.Mode[]>([]);
  const [saved, setSaved] = useState<api.Preferences | null>(null);
  const [mode, setMode] = useState<string>("DEFAULT");
  const [overrides, setOverrides] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);

  function adopt(prefs: api.Preferences) {
    setSaved(prefs);
    setMode(prefs.mode);
    const next: Record<string, number> = {};
    prefs.components.forEach((c) => {
      if (c.override !== null) next[c.code] = c.override;
    });
    setOverrides(next);
  }

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const [modesResult, prefsResult] = await Promise.all([api.modes(accessToken), api.preferences(accessToken)]);
      if (cancelled) return;
      onResult("GET /v1/feed/modes", modesResult);
      onResult("GET /v1/feed/preferences", prefsResult);
      if (modesResult.ok) setModes(modesResult.data);
      if (prefsResult.ok) adopt(prefsResult.data);
    })();
    return () => {
      cancelled = true;
    };
    // Load once per sign-in; onResult only appends to the request log.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken]);

  const selected = modes.find((m) => m.code === mode);
  if (!saved || !selected) {
    return (
      <section>
        <h2>Ranking settings</h2>
        <p className="hint">Loading…</p>
      </section>
    );
  }

  const weightOf = (code: string) => overrides[code] ?? selected.weights[code] ?? 0;
  // Only send what differs from the mode as published; the server treats the rest as "use the mode".
  const changed = Object.fromEntries(
    Object.entries(overrides).filter(([code, value]) => value !== (selected.weights[code] ?? 0)),
  );
  const dirty = mode !== saved.mode || JSON.stringify(changed) !== JSON.stringify(savedOverrides(saved));

  async function save() {
    setBusy(true);
    try {
      const result = await api.savePreferences(accessToken, { mode, weights: changed });
      onResult("PUT /v1/feed/preferences", result);
      if (result.ok) {
        adopt(result.data);
        onSaved();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>Ranking settings</h2>
      <p className="hint">
        These weights decide how videos are ordered in your feed. Each video&apos;s score is the sum of
        (component value × weight), and the feed will show you that sum for every video.
      </p>

      <label>
        Ranking mode
        <select
          value={mode}
          onChange={(e) => {
            setMode(e.target.value);
            setOverrides({}); // a new mode starts from its published weights
          }}
        >
          {modes.map((m) => (
            <option key={m.code} value={m.code}>
              {m.code} — {m.description}
            </option>
          ))}
        </select>
      </label>
      {selected.followedOnly && <p className="hint">This mode only shows creators you follow.</p>}

      {saved.components.map((c) => {
        const value = weightOf(c.code);
        const published = selected.weights[c.code] ?? 0;
        return (
          <label key={c.code} className="slider">
            <span>
              <strong>{c.code}</strong> <span className="hint">{c.description}</span>
            </span>
            <span className="row">
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={value}
                onChange={(e) =>
                  setOverrides({ ...overrides, [c.code]: Math.round(Number(e.target.value) * 100) / 100 })
                }
              />
              <code>{value.toFixed(2)}</code>
            </span>
            <span className="hint">
              mode default {published.toFixed(2)}
              {value !== published && " · customised"}
            </span>
          </label>
        );
      })}

      <div className="row">
        <button type="button" disabled={busy || !dirty} onClick={save}>
          Save settings
        </button>
        <button type="button" className="secondary" disabled={busy || Object.keys(overrides).length === 0}
          onClick={() => setOverrides({})}>
          Use mode as published
        </button>
      </div>
    </section>
  );
}

function savedOverrides(prefs: api.Preferences): Record<string, number> {
  return Object.fromEntries(prefs.components.filter((c) => c.override !== null).map((c) => [c.code, c.override as number]));
}
