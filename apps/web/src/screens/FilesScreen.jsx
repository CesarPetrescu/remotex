import { FileRow } from '../components/FileRow';
import { joinPath } from '../util/path';

export function FilesScreen({ state, onNavigate, onUp, onStartHere }) {
  const path = state.browsePath || '/';
  return (
    <div className="screen files-screen">
      <div className="section-title">pick a folder for the new session</div>
      <div className="cwd-card">
        <div className="cwd-label">CWD</div>
        <div className="cwd-path">{path}</div>
      </div>
      <div className="fs-controls">
        <button
          type="button"
          className="btn-surface"
          onClick={onUp}
          disabled={path === '/'}
        >
          ↑ up
        </button>
        <button
          type="button"
          className="btn-primary"
          onClick={onStartHere}
          disabled={state.browseLoading}
        >
          start session here
        </button>
      </div>
      {state.browseLoading && state.browseEntries.length === 0 ? (
        <div className="empty">loading…</div>
      ) : state.browseEntries.length === 0 ? (
        <div className="empty">empty</div>
      ) : (
        <div className="fs-list">
          {state.browseEntries.map((entry) => (
            <FileRow
              key={entry.fileName}
              entry={entry}
              onOpenDir={() => onNavigate(joinPath(path, entry.fileName))}
            />
          ))}
        </div>
      )}
    </div>
  );
}
