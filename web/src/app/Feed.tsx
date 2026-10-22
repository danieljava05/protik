"use client";

import { useEffect, useState } from "react";
import * as api from "@/lib/api";
import Comments from "./Comments";
import { megabytes } from "./DataBudget";

type Props = {
  accessToken: string;
  currentUserId: string | null;
  /** Called after the app reports bytes, so the budget meter reloads. */
  onUsage: () => void;
  onResult: (label: string, result: api.ApiResult<unknown>) => void;
};

/** One colour per component, in the order the API lists them. */
const COLOURS: Record<string, string> = {
  RECENCY: "var(--c-recency)",
  ENGAGEMENT: "var(--c-engagement)",
  FOLLOWING: "var(--c-following)",
  TOPIC: "var(--c-topic)",
};

export default function Feed({ accessToken, currentUserId, onUsage, onResult }: Props) {
  const [items, setItems] = useState<api.FeedItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [mode, setMode] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [explain, setExplain] = useState(true);
  const [openComments, setOpenComments] = useState<string | null>(null);
  const [policy, setPolicy] = useState<api.DataPolicy | null>(null);
  const [choice, setChoice] = useState<Record<string, string>>({});

  /** Stores a response (or its error). Called only after a response has arrived. */
  function apply(next: string | null, replace: boolean, result: api.ApiResult<api.FeedPage>) {
    onResult(next ? "GET /v1/feed (next page)" : "GET /v1/feed", result);
    setLoading(false);
    if (!result.ok) {
      setError(result.problem?.detail ?? "Could not load the feed.");
      return;
    }
    setMode(result.data.mode);
    setPolicy(result.data.dataPolicy);
    setCursor(result.data.nextCursor);
    setItems((previous) => (replace ? result.data.items : [...previous, ...result.data.items]));
  }

  /** Optimistic: show the change at once, ask the server, and undo it if the server says no. */
  async function toggleLike(item: api.FeedItem) {
    const target = !item.liked;
    patch(item.videoId, (i) => ({ ...i, liked: target, likes: Math.max(0, i.likes + (target ? 1 : -1)) }));
    const result = await api.setLike(accessToken, item.videoId, target);
    onResult(`${target ? "PUT" : "DELETE"} /v1/videos/…/like`, result);
    if (!result.ok) patch(item.videoId, (i) => ({ ...i, liked: item.liked, likes: item.likes }));
  }

  async function toggleFollow(item: api.FeedItem) {
    const target = !item.followingCreator;
    patchCreator(item.creatorId, (i) => ({ ...i, followingCreator: target }));
    const result = await api.setFollow(accessToken, item.creatorId, target);
    onResult(`${target ? "PUT" : "DELETE"} /v1/users/…/follow`, result);
    if (!result.ok) patchCreator(item.creatorId, (i) => ({ ...i, followingCreator: item.followingCreator }));
  }

  /** "Watch" a video at a quality: report the estimated bytes, as a real player would report what it downloaded. */
  async function watch(item: api.FeedItem, rendition: string) {
    const bytes = item.estimatedBytes?.[rendition];
    if (bytes === undefined) return;
    const reported = await api.reportUsage(accessToken, [{ videoId: item.videoId, rendition, bytes }]);
    onResult("POST /v1/data/usage", reported);
    if (!reported.ok) return;
    const summary = await api.usageSummary(accessToken);
    onResult("GET /v1/data/usage", summary);
    if (summary.ok) setPolicy(summary.data.policy);
    onUsage();
  }

  function chosenRendition(item: api.FeedItem): string | undefined {
    const options = Object.keys(item.estimatedBytes ?? {});
    return choice[item.videoId] ?? (policy?.recommendedRendition && options.includes(policy.recommendedRendition)
      ? policy.recommendedRendition
      : options.includes("480p") ? "480p" : options[0]);
  }

  function patch(videoId: string, change: (item: api.FeedItem) => api.FeedItem) {
    setItems((previous) => previous.map((i) => (i.videoId === videoId ? change(i) : i)));
  }

  function patchCreator(creatorId: string, change: (item: api.FeedItem) => api.FeedItem) {
    setItems((previous) => previous.map((i) => (i.creatorId === creatorId ? change(i) : i)));
  }

  /** Used by buttons: show the loading state, then fetch. */
  async function begin(next: string | null, replace: boolean, withExplain: boolean = explain) {
    setLoading(true);
    setError(null);
    apply(next, replace, await api.feed(accessToken, { cursor: next, explain: withExplain }));
  }

  // The parent remounts this component (new key) when the viewer signs in or changes ranking
  // settings, so one fetch on mount is a fresh session under the current rules.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const result = await api.feed(accessToken, { explain: true });
      if (!cancelled) apply(null, true, result);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <section>
      <h2>Your feed {mode && <span className="hint">· {mode}</span>}</h2>
      <p className="hint">
        Sample videos only: there is no upload yet. Each bar shows how a video&apos;s score is built from the
        components and your weights; change the sliders above and the order changes.
      </p>
      {policy && (
        <p className={`advice ${policy.state.toLowerCase()}`}>
          Data advice: <strong>{policy.state}</strong>
          {policy.recommendedRendition ? ` · use ${policy.recommendedRendition} or lower` : " · any quality is fine"}
          {" · "}preload up to {megabytes(policy.prefetchBudgetBytes)}
          {policy.remainingBytes !== null && ` · ${megabytes(policy.remainingBytes)} left`}
        </p>
      )}
      <label className="check">
        <input
          type="checkbox"
          checked={explain}
          onChange={(e) => {
            setExplain(e.target.checked);
            void begin(null, true, e.target.checked);
          }}
        />{" "}
        Show how each score is made (turn off to save data)
      </label>

      {error && <p className="error">{error}</p>}
      {!loading && !error && items.length === 0 && (
        <p className="hint">
          Nothing to show yet. Start the backend with <code>--protik.demo.seed=true</code> to load sample videos.
        </p>
      )}

      <ol className="feed">
        {items.map((item, index) => (
          <li key={item.videoId}>
            <div className="line">
              <strong>
                #{index + 1} · video {item.videoId.slice(-6)}
              </strong>
              <span>score {item.score.toFixed(3)}</span>
            </div>
            <div className="meta">
              creator {item.creatorId.slice(-6)} · {item.language ?? "?"} · {Math.round(item.durationMs / 1000)} s ·{" "}
              {ago(item.publishedAt)}
            </div>
            <div className="row actions">
              <button
                type="button"
                className={item.liked ? "toggle on" : "toggle"}
                aria-pressed={item.liked}
                onClick={() => void toggleLike(item)}
              >
                {item.liked ? "♥ Liked" : "♡ Like"} · {item.likes}
              </button>
              <button
                type="button"
                className={item.followingCreator ? "toggle on" : "toggle"}
                aria-pressed={item.followingCreator}
                onClick={() => void toggleFollow(item)}
              >
                {item.followingCreator ? "Following" : "Follow creator"}
              </button>
            </div>
            {item.estimatedBytes && chosenRendition(item) && (
              <div className="row cost">
                <select
                  aria-label="Quality"
                  value={chosenRendition(item)}
                  onChange={(e) => setChoice({ ...choice, [item.videoId]: e.target.value })}
                >
                  {Object.entries(item.estimatedBytes).map(([code, bytes]) => (
                    <option key={code} value={code}>
                      {code} · about {megabytes(bytes)}
                    </option>
                  ))}
                </select>
                <button type="button" className="toggle" onClick={() => void watch(item, chosenRendition(item) as string)}>
                  Watch (reports usage)
                </button>
              </div>
            )}
            <button
              type="button"
              className="toggle"
              aria-expanded={openComments === item.videoId}
              onClick={() => setOpenComments(openComments === item.videoId ? null : item.videoId)}
            >
              {openComments === item.videoId ? "Hide comments" : `Comments · ${item.comments}`}
            </button>
            {openComments === item.videoId && (
              <Comments
                accessToken={accessToken}
                videoId={item.videoId}
                currentUserId={currentUserId}
                onResult={onResult}
              />
            )}
            {item.explanation && <Breakdown lines={item.explanation} />}
          </li>
        ))}
      </ol>

      <div className="row">
        <button type="button" disabled={loading || !cursor} onClick={() => void begin(cursor, false)}>
          {loading ? "Loading…" : cursor ? "Load more" : "End of feed"}
        </button>
        <button type="button" className="secondary" disabled={loading} onClick={() => void begin(null, true)}>
          Start a fresh feed
        </button>
      </div>
    </section>
  );
}

function Breakdown({ lines }: { lines: api.ExplanationLine[] }) {
  const total = lines.reduce((sum, l) => sum + l.contribution, 0);
  return (
    <div className="breakdown">
      <div className="bar" role="img" aria-label="score made of components">
        {lines
          .filter((l) => l.contribution > 0)
          .map((l) => (
            <span
              key={l.component}
              style={{ width: `${total > 0 ? (l.contribution / total) * 100 : 0}%`, background: COLOURS[l.component] }}
              title={`${l.component}: ${l.value.toFixed(2)} × ${l.weight.toFixed(2)}`}
            />
          ))}
      </div>
      <ul className="legend">
        {lines.map((l) => (
          <li key={l.component}>
            <i style={{ background: COLOURS[l.component] }} />
            {l.component} {l.value.toFixed(2)} × {l.weight.toFixed(2)} = <b>{l.contribution.toFixed(3)}</b>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ago(iso: string): string {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 60 * 48) return `${Math.round(minutes / 60)} h ago`;
  return `${Math.round(minutes / 1440)} days ago`;
}
