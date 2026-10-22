"use client";

import { useEffect, useState } from "react";
import * as api from "@/lib/api";

type Props = {
  accessToken: string;
  /** Bumped when usage changes elsewhere (for example after "watching" a video), so the meter reloads. */
  version: number;
  onResult: (label: string, result: api.ApiResult<unknown>) => void;
  /** Called after settings are saved so the feed's advice and estimates refresh. */
  onSaved: () => void;
};

/** Format bytes the way a person on a data plan thinks about them. */
export function megabytes(bytes: number): string {
  if (bytes < 100_000) return `${(bytes / 1000).toFixed(0)} KB`;
  return `${(bytes / 1_000_000).toFixed(bytes < 10_000_000 ? 2 : 1)} MB`;
}

/**
 * Data saver, monthly budget and prefetch cap (VISION.md goal 2). The server advises and never blocks
 * playback; usage is whatever the app reports, so the meter is a convenience, not a guarantee.
 */
export default function DataBudget({ accessToken, version, onResult, onSaved }: Props) {
  const [settings, setSettings] = useState<api.DataSettings | null>(null);
  const [summary, setSummary] = useState<api.UsageSummary | null>(null);
  const [capText, setCapText] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const [s, u] = await Promise.all([api.dataSettings(accessToken), api.usageSummary(accessToken)]);
      if (cancelled) return;
      onResult("GET /v1/data/settings", s);
      onResult("GET /v1/data/usage", u);
      if (s.ok) {
        setSettings(s.data);
        setCapText(s.data.monthlyCapMb === null ? "" : String(s.data.monthlyCapMb));
      }
      if (u.ok) setSummary(u.data);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken, version]);

  if (!settings) {
    return (
      <section>
        <h2>Data budget</h2>
        <p className="hint">Loading…</p>
      </section>
    );
  }

  async function save() {
    if (!settings) return;
    setBusy(true);
    try {
      const cap = capText.trim() === "" ? null : Number(capText);
      const result = await api.saveDataSettings(accessToken, { ...settings, monthlyCapMb: cap });
      onResult("PUT /v1/data/settings", result);
      if (result.ok) {
        setSettings(result.data);
        const refreshed = await api.usageSummary(accessToken);
        if (refreshed.ok) setSummary(refreshed.data);
        onSaved();
      }
    } finally {
      setBusy(false);
    }
  }

  const used = summary?.usedBytes ?? 0;
  const cap = summary?.capBytes ?? null;
  const fraction = cap ? Math.min(1, used / cap) : 0;

  return (
    <section>
      <h2>Data budget</h2>
      <p className="hint">
        The server tells your app what to do (which quality, how much to preload); it never blocks a video. Usage is what the
        app reports, so treat the meter as a guide.
      </p>

      <label className="check">
        <input
          type="checkbox"
          checked={settings.dataSaver}
          onChange={(e) => setSettings({ ...settings, dataSaver: e.target.checked })}
        />{" "}
        Data saver (cheapest video, no preloading)
      </label>
      <div className="grid">
        <label>
          Monthly data budget (MB, empty = none)
          <input value={capText} inputMode="numeric" onChange={(e) => setCapText(e.target.value)} />
        </label>
        <label>
          Preload at most (MB)
          <input
            type="number"
            min={0}
            max={1000}
            value={settings.prefetchCapMb}
            onChange={(e) => setSettings({ ...settings, prefetchCapMb: Number(e.target.value) })}
          />
        </label>
        <label>
          My plan renews on day (1 to 28)
          <input
            type="number"
            min={1}
            max={28}
            value={settings.billingDay}
            onChange={(e) => setSettings({ ...settings, billingDay: Number(e.target.value) })}
          />
        </label>
      </div>
      <button type="button" disabled={busy} onClick={() => void save()}>
        Save data settings
      </button>

      {summary && (
        <div className="meter">
          <div className="line">
            <strong>
              {megabytes(used)} used{cap ? ` of ${megabytes(cap)}` : ""}
            </strong>
            <span className={`state ${summary.policy.state.toLowerCase()}`}>{summary.policy.state}</span>
          </div>
          {cap && (
            <div className="bar" role="progressbar" aria-valuenow={Math.round(fraction * 100)} aria-valuemin={0} aria-valuemax={100}>
              <span style={{ width: `${fraction * 100}%`, background: fraction >= 0.9 ? "var(--bad)" : "var(--accent)" }} />
            </div>
          )}
          <p className="hint">
            Period {summary.periodStart} to {summary.periodEnd}.{" "}
            {summary.policy.recommendedRendition
              ? `Advice: use ${summary.policy.recommendedRendition} or lower. `
              : "No restriction advised. "}
            Preload up to {megabytes(summary.policy.prefetchBudgetBytes)}.
          </p>
        </div>
      )}
    </section>
  );
}
