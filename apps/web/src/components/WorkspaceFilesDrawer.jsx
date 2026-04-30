import { useEffect, useState, useCallback } from 'react';

/**
 * Slide-in drawer (right-aligned) showing the contents of the current
 * chat's cwd. Each file has rename / delete / download. Distinct from
 * the existing Files screen — that's for picking a folder to start a
 * session in; this lives inside an active chat and operates on the
 * session's workspace.
 */
export function WorkspaceFilesDrawer({
  open,
  initialPath,
  hostId,
  apiRef,
  onClose,
}) {
  const [path, setPath] = useState(initialPath || '/');
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [renameTarget, setRenameTarget] = useState(null);
  const [renameValue, setRenameValue] = useState('');

  const refresh = useCallback(async (p) => {
    if (!hostId) return;
    setLoading(true); setError(null);
    try {
      const r = await apiRef.current.readDirectory(hostId, p);
      setEntries(r.entries || []);
    } catch (e) {
      setError(String(e.message || e));
    } finally {
      setLoading(false);
    }
  }, [hostId, apiRef]);

  useEffect(() => {
    if (open) {
      setPath(initialPath || '/');
      refresh(initialPath || '/');
    }
  }, [open, initialPath, refresh]);

  if (!open) return null;

  const join = (p, n) => (p.endsWith('/') ? p + n : `${p}/${n}`);

  const onDownload = async (entry) => {
    try {
      const r = await apiRef.current.readFile(hostId, join(path, entry.fileName));
      const bytes = atob(r.base64);
      const arr = new Uint8Array(bytes.length);
      for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
      const blob = new Blob([arr], { type: r.mime || 'application/octet-stream' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = entry.fileName;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
    } catch (e) {
      alert(`Download failed: ${e.message || e}`);
    }
  };

  const onConfirmDelete = async (entry) => {
    if (!confirm(`Delete ${entry.fileName}? This cannot be undone.`)) return;
    try {
      await apiRef.current.deleteFile(hostId, join(path, entry.fileName));
      refresh(path);
    } catch (e) {
      alert(`Delete failed: ${e.message || e}`);
    }
  };

  const onConfirmRename = async () => {
    const newName = renameValue.trim();
    if (!renameTarget || !newName || newName === renameTarget.fileName) {
      setRenameTarget(null);
      return;
    }
    try {
      await apiRef.current.renameFile(
        hostId,
        join(path, renameTarget.fileName),
        join(path, newName),
      );
      setRenameTarget(null);
      refresh(path);
    } catch (e) {
      alert(`Rename failed: ${e.message || e}`);
    }
  };

  return (
    <div className="ws-drawer-scrim" onClick={onClose}>
      <aside
        className="ws-drawer"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="Workspace files"
      >
        <header className="ws-drawer-head">
          <span className="ws-drawer-title">WORKSPACE FILES</span>
          <button type="button" className="ws-drawer-close" onClick={onClose}>×</button>
        </header>
        <div className="ws-drawer-path">
          {path !== '/' && (
            <button
              type="button"
              className="ws-drawer-up"
              onClick={() => {
                const next = path.split('/').slice(0, -1).join('/') || '/';
                setPath(next); refresh(next);
              }}
            >↑ up</button>
          )}
          <span className="ws-drawer-cwd">{path}</span>
        </div>
        {error && <div className="ws-drawer-error">{error}</div>}
        {loading ? (
          <div className="ws-drawer-empty">loading…</div>
        ) : entries.length === 0 ? (
          <div className="ws-drawer-empty">empty</div>
        ) : (
          <ul className="ws-drawer-list">
            {entries.map((entry) => (
              <li key={entry.fileName} className="ws-drawer-row">
                <span className={`ws-drawer-icon ${entry.isDirectory ? 'dir' : 'file'}`} />
                <button
                  type="button"
                  className="ws-drawer-name"
                  onClick={() => {
                    if (entry.isDirectory) {
                      const next = join(path, entry.fileName);
                      setPath(next); refresh(next);
                    }
                  }}
                  disabled={!entry.isDirectory}
                >
                  {entry.fileName}
                </button>
                <div className="ws-drawer-actions">
                  {!entry.isDirectory && (
                    <button type="button" onClick={() => onDownload(entry)}>↓</button>
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      setRenameTarget(entry);
                      setRenameValue(entry.fileName);
                    }}
                  >ren</button>
                  {!entry.isDirectory && (
                    <button
                      type="button"
                      className="ws-drawer-del"
                      onClick={() => onConfirmDelete(entry)}
                    >del</button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
        {renameTarget && (
          <div className="ws-drawer-rename">
            <input
              type="text"
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') onConfirmRename();
                if (e.key === 'Escape') setRenameTarget(null);
              }}
              autoFocus
            />
            <button type="button" onClick={onConfirmRename}>rename</button>
            <button type="button" onClick={() => setRenameTarget(null)}>cancel</button>
          </div>
        )}
      </aside>
    </div>
  );
}
