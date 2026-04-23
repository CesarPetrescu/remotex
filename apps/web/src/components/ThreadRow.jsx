import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';

export function ThreadRow({ thread, onClick }) {
  const hasSpecificTitle = thread.title && thread.title_is_generic === false;
  const title = hasSpecificTitle ? thread.title : thread.preview || '(no preview)';
  const description = hasSpecificTitle ? thread.description || thread.preview : null;
  return (
    <button type="button" className="thread-row" onClick={() => onClick(thread)}>
      <div className="thread-preview">{title}</div>
      {description && <div className="thread-description">{description}</div>}
      <div className="thread-meta">
        <span>{relativeAge(thread.updated_at ?? thread.created_at)}</span>
        <span>· {thread.id.slice(0, 8)}…</span>
        {thread.cwd && <span>· {shortenCwd(thread.cwd)}</span>}
      </div>
    </button>
  );
}
