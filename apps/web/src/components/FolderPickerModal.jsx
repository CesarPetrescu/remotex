import { useCallback, useEffect, useMemo, useState } from 'react';
import { joinPath, parentPath } from '../util/path';

// Modal folder browser: breadcrumb path, directory list, "New folder"
// affordance, and a primary "Use this folder" action. Loads directories
// lazily from the relay/daemon fs API via the caller-supplied handlers.
//
// Props:
//   open, onClose
//   initialPath
//   onSelect(path)                 — final pick
//   onListDirectory(path)          — returns { path, entries }
//   onCreateFolder(parent, name)   — mkdir; throws on failure
export function FolderPickerModal({
  open,
  onClose,
  initialPath = '/',
  onSelect,
  onListDirectory,
  onCreateFolder,
}) {
  const [path, setPath] = useState(initialPath || '/');
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [newFolder, setNewFolder] = useState(null); // null | {name}
  const [creating, setCreating] = useState(false);
  const [manual, setManual] = useState('');

  const loadPath = useCallback(
    async (p) => {
      if (!onListDirectory) return;
      setLoading(true);
      setError('');
      try {
        const r = await onListDirectory(p);
        const sorted = (r.entries || []).slice().sort((a, b) => {
          if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
          return a.fileName.localeCompare(b.fileName, undefined, { sensitivity: 'base' });
        });
        setPath(r.path || p);
        setEntries(sorted);
      } catch (e) {
        setError(e.message || 'failed to read directory');
        setEntries([]);
      } finally {
        setLoading(false);
      }
    },
    [onListDirectory],
  );

  // Reset + load whenever the modal opens.
  useEffect(() => {
    if (!open) return;
    setNewFolder(null);
    setManual('');
    loadPath(initialPath || '/');
  }, [open, initialPath, loadPath]);

  const crumbs = useMemo(() => {
    if (!path || path === '/') return [{ label: '/', target: '/' }];
    const parts = path.split('/').filter(Boolean);
    const out = [{ label: '/', target: '/' }];
    let acc = '';
    for (const p of parts) {
      acc += `/${p}`;
      out.push({ label: p, target: acc });
    }
    return out;
  }, [path]);

  const dirs = entries.filter((e) => e.isDirectory);
  const files = entries.filter((e) => !e.isDirectory);

  const goUp = () => {
    if (path && path !== '/') loadPath(parentPath(path));
  };

  const handleCreate = async () => {
    if (!newFolder || !newFolder.name.trim()) return;
    setCreating(true);
    setError('');
    try {
      await onCreateFolder(path, newFolder.name.trim());
      setNewFolder(null);
      await loadPath(path);
    } catch (e) {
      setError(e.message || 'mkdir failed');
    } finally {
      setCreating(false);
    }
  };

  const handleGoto = () => {
    const t = manual.trim();
    if (t) loadPath(t);
  };

  if (!open) return null;

  return (
    <div className="fp-scrim" onClick={onClose}>
      <div
        className="fp-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Select folder"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="fp-header">
          <div className="fp-eyebrow">SELECT FOLDER</div>
          <button
            type="button"
            className="fp-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </header>

        <div className="fp-breadcrumbs">
          <button
            type="button"
            className="fp-up"
            onClick={goUp}
            disabled={!path || path === '/'}
            title="Parent directory"
          >
            ↑
          </button>
          <div className="fp-crumb-list">
            {crumbs.map((c, i) => (
              <span key={c.target} className="fp-crumb-item">
                <button
                  type="button"
                  className={`fp-crumb${i === crumbs.length - 1 ? ' active' : ''}`}
                  onClick={() => loadPath(c.target)}
                >
                  {c.label}
                </button>
                {i < crumbs.length - 1 && <span className="fp-sep">/</span>}
              </span>
            ))}
          </div>
        </div>

        <div className="fp-toolbar">
          <div className="fp-manual">
            <input
              type="text"
              value={manual}
              onChange={(e) => setManual(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleGoto();
              }}
              placeholder="go to path…"
              spellCheck={false}
            />
            <button
              type="button"
              className="btn-surface btn-sm"
              onClick={handleGoto}
              disabled={!manual.trim()}
            >
              Go
            </button>
          </div>
          <button
            type="button"
            className="btn-surface btn-sm fp-new"
            onClick={() => setNewFolder({ name: '' })}
            disabled={newFolder !== null}
          >
            + New folder
          </button>
        </div>

        {newFolder && (
          <div className="fp-new-row">
            <span className="fp-new-prefix">▤</span>
            <input
              autoFocus
              type="text"
              value={newFolder.name}
              onChange={(e) => setNewFolder({ name: e.target.value })}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreate();
                if (e.key === 'Escape') setNewFolder(null);
              }}
              placeholder="folder name"
              spellCheck={false}
              disabled={creating}
            />
            <button
              type="button"
              className="btn-primary btn-sm"
              onClick={handleCreate}
              disabled={creating || !newFolder.name.trim()}
            >
              Create
            </button>
            <button
              type="button"
              className="btn-surface btn-sm"
              onClick={() => setNewFolder(null)}
              disabled={creating}
            >
              Cancel
            </button>
          </div>
        )}

        <div className="fp-list">
          {loading && entries.length === 0 ? (
            <div className="fp-empty">loading…</div>
          ) : error ? (
            <div className="fp-empty fp-error">{error}</div>
          ) : dirs.length === 0 && files.length === 0 ? (
            <div className="fp-empty">empty directory</div>
          ) : (
            <>
              {dirs.map((e) => (
                <button
                  type="button"
                  key={`d-${e.fileName}`}
                  className="fp-row fp-row-dir"
                  onClick={() => loadPath(joinPath(path, e.fileName))}
                  onDoubleClick={() => onSelect(joinPath(path, e.fileName))}
                  title={`Open ${e.fileName}`}
                >
                  <span className="fp-row-icon">▤</span>
                  <span className="fp-row-name">{e.fileName}</span>
                  <span className="fp-row-arrow">›</span>
                </button>
              ))}
              {files.map((e) => (
                <div
                  key={`f-${e.fileName}`}
                  className="fp-row fp-row-file"
                  title="files cannot be selected as a cwd"
                >
                  <span className="fp-row-icon">◦</span>
                  <span className="fp-row-name">{e.fileName}</span>
                </div>
              ))}
            </>
          )}
        </div>

        <footer className="fp-footer">
          <div className="fp-current">
            <span className="fp-current-label">Use</span>
            <code className="fp-current-path">{path}</code>
          </div>
          <div className="fp-footer-actions">
            <button type="button" className="btn-surface" onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className="btn-primary"
              onClick={() => onSelect(path)}
            >
              Use this folder
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}
