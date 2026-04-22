import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';

export function ThreadRow({ thread, onClick }) {
  return (
    <button type="button" className="thread-row" onClick={() => onClick(thread)}>
      <div className="thread-preview">{thread.preview || '(no preview)'}</div>
      <div className="thread-meta">
        <span>{relativeAge(thread.updated_at ?? thread.created_at)}</span>
        <span>· {thread.id.slice(0, 8)}…</span>
        {thread.cwd && <span>· {shortenCwd(thread.cwd)}</span>}
      </div>
    </button>
  );
}
