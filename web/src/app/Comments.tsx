"use client";

import { useEffect, useState } from "react";
import * as api from "@/lib/api";

type Props = {
  accessToken: string;
  videoId: string;
  currentUserId: string | null;
  onResult: (label: string, result: api.ApiResult<unknown>) => void;
};

/**
 * Comments under one video. Text is shown as plain text (React escapes it), which is what the API
 * promises: it stores exactly what was typed and never treats it as markup.
 */
export default function Comments({ accessToken, videoId, currentUserId, onResult }: Props) {
  const [items, setItems] = useState<api.CommentItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState("");
  // One key per intended post: if the request is retried after a dropped connection it posts once.
  const [postKey, setPostKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const [replies, setReplies] = useState<Record<string, api.CommentItem[]>>({});

  function apply(replace: boolean, result: api.ApiResult<api.CommentPage>) {
    setLoading(false);
    if (!result.ok) {
      setError(result.problem?.detail ?? "Could not load comments.");
      return;
    }
    setCursor(result.data.nextCursor);
    setItems((previous) => (replace ? result.data.items : [...previous, ...result.data.items]));
  }

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const result = await api.comments(accessToken, videoId, null);
      if (!cancelled) {
        onResult("GET /v1/videos/…/comments", result);
        apply(true, result);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken, videoId]);

  async function loadMore() {
    setLoading(true);
    const result = await api.comments(accessToken, videoId, cursor);
    onResult("GET /v1/videos/…/comments (next page)", result);
    apply(false, result);
  }

  async function showReplies(commentId: string) {
    const result = await api.replies(accessToken, commentId);
    onResult("GET /v1/comments/…/replies", result);
    if (result.ok) setReplies((previous) => ({ ...previous, [commentId]: result.data.items }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const key = postKey ?? crypto.randomUUID();
    setPostKey(key);
    setError(null);
    const result = await api.postComment(accessToken, videoId, text, replyTo, key);
    onResult("POST /v1/videos/…/comments", result);
    if (!result.ok) {
      setError(result.problem?.detail ?? "Could not post the comment.");
      return; // the same key is kept, so pressing the button again is a safe retry
    }
    setPostKey(null);
    setText("");
    if (replyTo) {
      const parent = replyTo;
      setReplyTo(null);
      setItems((previous) => previous.map((c) => (c.id === parent ? { ...c, replyCount: c.replyCount + 1 } : c)));
      await showReplies(parent);
    } else {
      const posted = result.data;
      setItems((previous) => [
        { id: posted.id, authorId: posted.authorId, body: posted.body, createdAt: posted.createdAt, replyCount: 0 },
        ...previous,
      ]);
    }
  }

  async function remove(comment: api.CommentItem, parentId: string | null) {
    const result = await api.deleteComment(accessToken, comment.id);
    onResult("DELETE /v1/comments/…", result);
    if (!result.ok) return;
    if (parentId) {
      setReplies((previous) => ({ ...previous, [parentId]: (previous[parentId] ?? []).filter((r) => r.id !== comment.id) }));
      setItems((previous) => previous.map((c) => (c.id === parentId ? { ...c, replyCount: Math.max(0, c.replyCount - 1) } : c)));
    } else {
      setItems((previous) => previous.filter((c) => c.id !== comment.id));
    }
  }

  const row = (c: api.CommentItem, parentId: string | null) => (
    <li key={c.id}>
      <div className="meta">
        {c.authorId.slice(-6)} · {new Date(c.createdAt).toLocaleString()}
      </div>
      <div className="comment-body">{c.body}</div>
      <div className="row">
        {parentId === null && (
          <button type="button" className="toggle" onClick={() => setReplyTo(replyTo === c.id ? null : c.id)}>
            Reply
          </button>
        )}
        {parentId === null && c.replyCount > 0 && !replies[c.id] && (
          <button type="button" className="toggle" onClick={() => void showReplies(c.id)}>
            Show {c.replyCount} {c.replyCount === 1 ? "reply" : "replies"}
          </button>
        )}
        {c.authorId === currentUserId && (
          <button type="button" className="toggle" onClick={() => void remove(c, parentId)}>
            Delete
          </button>
        )}
      </div>
      {parentId === null && replies[c.id] && (
        <ul className="replies">{replies[c.id].map((r) => row(r, c.id))}</ul>
      )}
    </li>
  );

  return (
    <div className="comments">
      <form onSubmit={submit}>
        {replyTo && <p className="hint">Replying to a comment. <button type="button" className="toggle" onClick={() => setReplyTo(null)}>Cancel</button></p>}
        <label>
          {replyTo ? "Your reply" : "Add a comment"}
          <input value={text} onChange={(e) => setText(e.target.value)} maxLength={2200} required />
        </label>
        <button disabled={text.trim().length === 0}>Post</button>
        {error && <span className="error"> {error}</span>}
      </form>
      {!loading && items.length === 0 && <p className="hint">No comments yet.</p>}
      <ul className="comment-list">{items.map((c) => row(c, null))}</ul>
      {cursor && (
        <button type="button" className="secondary" disabled={loading} onClick={() => void loadMore()}>
          {loading ? "Loading…" : "More comments"}
        </button>
      )}
    </div>
  );
}
