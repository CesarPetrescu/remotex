import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { joinPath, parentPath } from '../util/path';
import { fuzzyMatch } from '../util/fuzzy';
import {
  getRecents,
  getFavorites,
  isFavorite,
  toggleFavorite,
} from '../util/folderHistory';

// Jump — one unified folder picker. It opens on your home dir (or last cwd)
// with PINNED + RECENT shortcuts on top for fast recall. Clicking ANY row —
// a shortcut, a subfolder, or a typed /path suggestion — navigates INTO that
// folder; a clickable breadcrumb walks back up. The folder you're standing in
// is always committable via the persistent "Select this folder" bar at the
// bottom (or ⌘/Ctrl+Enter). Selection is never a hidden gesture. The search
// box fuzzy-filters the current view, and a /path or ~/path teleports.
//
// Props:
//   open, onClose
//   hostId, hostHome, hostName
//   initialPath
//   onListDirectory(path) -> { path, entries:[{fileName,isDirectory}] }
//   onCreateFolder(parent, name)
//   onSelect(path)                 — commit the chosen cwd
export function JumpPicker({
  open,
  onClose,
  hostId,
  hostHome = '/',
  hostName = '',
  initialPath = '/',
  onListDirectory,
  onCreateFolder,
  onSelect,
}) {
  const home = hostHome || '/';
  const [path, setPath] = useState(initialPath || home);
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [hi, setHi] = useState(0);
  const [favTick, setFavTick] = useState(0);
  const [newFolder, setNewFolder] = useState(null); // null | { name }
  const [creating, setCreating] = useState(false);
  const [psParent, setPsParent] = useState(null);
  const [psEntries, setPsEntries] = useState([]);
  const [psLoading, setPsLoading] = useState(false);

  const inputRef = useRef(null);
  const reqRef = useRef(0);
  const landingRef = useRef(initialPath || home);

  const queryTrim = query.trim();
  const isPathQuery = queryTrim.startsWith('/') || queryTrim.startsWith('~');

  const expand = useCallback(
    (raw) => {
      let s = (raw || '').trim();
      if (s === '~') return home;
      if (s.startsWith('~/')) return joinPath(home, s.slice(2));
      if (!s.startsWith('/')) s = `/${s}`;
      return s;
    },
    [home],
  );

  const baseName = (p) => {
    if (!p || p === '/') return '/';
    const t = p.endsWith('/') ? p.slice(0, -1) : p;
    return t.slice(t.lastIndexOf('/') + 1);
  };
  const display = useCallback(
    (p) => {
      if (p === home) return '~';
      if (home !== '/' && p.startsWith(`${home}/`)) return `~${p.slice(home.length)}`;
      return p;
    },
    [home],
  );

  const load = useCallback(
    async (p) => {
      if (!onListDirectory) return;
      setLoading(true);
      setError('');
      const mine = ++reqRef.current;
      try {
        const r = await onListDirectory(p);
        if (mine !== reqRef.current) return;
        const dirs = (r.entries || [])
          .filter((e) => e.isDirectory)
          .sort((a, b) =>
            a.fileName.localeCompare(b.fileName, undefined, { sensitivity: 'base' }),
          );
        setPath(r.path || p);
        setEntries(dirs);
        setHi(0);
        setQuery('');
        setNewFolder(null);
      } catch (e) {
        if (mine !== reqRef.current) return;
        setError(e.message || 'failed to read directory');
        setEntries([]);
      } finally {
        if (mine === reqRef.current) setLoading(false);
      }
    },
    [onListDirectory],
  );

  // Reset + load on open.
  useEffect(() => {
    if (!open) return;
    landingRef.current = initialPath || home;
    setQuery('');
    setNewFolder(null);
    setPsParent(null);
    setPsEntries([]);
    load(initialPath || home);
    const t = setTimeout(() => inputRef.current?.focus(), 30);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialPath]);

  // Path-autocomplete: list the parent of the typed path when it changes.
  useEffect(() => {
    if (!open || !isPathQuery) return;
    const s = expand(query);
    const parent = s.endsWith('/') ? (s.replace(/\/+$/, '') || '/') : parentPath(s);
    if (parent === psParent) return;
    const h = setTimeout(async () => {
      setPsLoading(true);
      const mine = ++reqRef.current;
      try {
        const r = await onListDirectory(parent);
        if (mine !== reqRef.current) return;
        setPsParent(parent);
        setPsEntries((r.entries || []).filter((e) => e.isDirectory));
      } catch {
        if (mine !== reqRef.current) return;
        setPsParent(parent);
        setPsEntries([]);
      } finally {
        if (mine === reqRef.current) setPsLoading(false);
      }
    }, 220);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, open, isPathQuery]);

  const favorites = useMemo(() => getFavorites(hostId), [hostId, favTick, open]);
  const recents = useMemo(() => getRecents(hostId), [hostId, favTick, open]);
  const atLanding = path === landingRef.current;

  const sections = useMemo(() => {
    const filt = (items) => {
      if (!queryTrim) return items;
      return items
        .map((it) => ({ it, m: fuzzyMatch(queryTrim, it.name) }))
        .filter((x) => x.m)
        .sort((a, b) => b.m.score - a.m.score)
        .map((x) => ({ ...x.it, indices: x.m.indices }));
    };

    // Path teleport mode: rows are subdirs of the typed parent.
    if (isPathQuery) {
      const s = expand(query);
      const base = s.endsWith('/') ? '' : baseName(s);
      const parent = s.endsWith('/') ? (s.replace(/\/+$/, '') || '/') : parentPath(s);
      let rows = psEntries.map((e) => ({ path: joinPath(parent, e.fileName), name: e.fileName }));
      if (base) {
        rows = rows
          .map((r) => ({ r, m: fuzzyMatch(base, r.name) }))
          .filter((x) => x.m)
          .sort((a, b) => b.m.score - a.m.score)
          .map((x) => ({ ...x.r, indices: x.m.indices }));
      }
      return [{ label: psLoading ? 'PATH · listing…' : 'PATH', items: rows }];
    }

    const favSet = new Set(favorites.map((f) => f.path));
    const out = [];
    if (atLanding) {
      const pinned = favorites.map((f) => ({
        path: f.path,
        name: baseName(f.path),
        parent: display(parentPath(f.path)),
        fav: true,
        anchor: true,
      }));
      const recent = recents.map((r) => ({
        path: r.path,
        name: baseName(r.path),
        parent: display(parentPath(r.path)),
        fav: favSet.has(r.path),
        anchor: true,
      }));
      const fp = filt(pinned);
      const fr = filt(recent);
      if (fp.length) out.push({ label: 'PINNED', items: fp });
      if (fr.length) out.push({ label: 'RECENT', items: fr });
    }
    const folders = entries.map((e) => {
      const p = joinPath(path, e.fileName);
      return { path: p, name: e.fileName, fav: favSet.has(p) };
    });
    out.push({ label: atLanding ? 'FOLDERS' : null, items: filt(folders) });
    return out;
  }, [queryTrim, isPathQuery, expand, query, psEntries, psLoading, favorites, recents, entries, path, atLanding, display]);

  const flat = useMemo(() => sections.flatMap((s) => s.items), [sections]);
  useEffect(() => {
    if (hi >= flat.length) setHi(Math.max(0, flat.length - 1));
  }, [flat.length, hi]);

  const crumbs = useMemo(() => {
    const out = [];
    if (home !== '/' && (path === home || path.startsWith(`${home}/`))) {
      out.push({ label: '~', target: home });
      let acc = home;
      for (const seg of path.slice(home.length).split('/').filter(Boolean)) {
        acc = joinPath(acc, seg);
        out.push({ label: seg, target: acc });
      }
    } else {
      out.push({ label: '/', target: '/' });
      let acc = '';
      for (const seg of path.split('/').filter(Boolean)) {
        acc += `/${seg}`;
        out.push({ label: seg, target: acc });
      }
    }
    return out;
  }, [path, home]);

  const selectCurrent = useCallback(() => onSelect(path), [onSelect, path]);
  const pin = useCallback(
    (p) => {
      toggleFavorite(hostId, p);
      setFavTick((n) => n + 1);
    },
    [hostId],
  );

  const handleCreate = async () => {
    if (!newFolder || !newFolder.name.trim()) return;
    setCreating(true);
    setError('');
    try {
      await onCreateFolder(path, newFolder.name.trim());
      setNewFolder(null);
      await load(path);
    } catch (e) {
      setError(e.message || 'mkdir failed');
    } finally {
      setCreating(false);
    }
  };

  const onKey = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHi((i) => Math.min(i + 1, flat.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHi((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (e.metaKey || e.ctrlKey) {
        selectCurrent();
        return;
      }
      if (flat[hi]) load(flat[hi].path);
      else if (isPathQuery) load(psParent || expand(query));
    } else if (e.key === '.' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      if (flat[hi]) pin(flat[hi].path);
    } else if (e.key === 'Backspace' && query === '' && path !== '/') {
      e.preventDefault();
      load(parentPath(path));
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    }
  };

  if (!open) return null;

  let running = -1;

  const node = (
    <div className="jp-scrim" onClick={onClose}>
      <div
        className="jp-modal"
        role="dialog"
        aria-modal="true"
        aria-label="Choose working directory"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="jp-input-row">
          <span className="jp-glyph">›</span>
          <input
            ref={inputRef}
            className="jp-input"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setHi(0);
            }}
            onKeyDown={onKey}
            placeholder="filter this folder — or type a /path to jump"
            spellCheck={false}
            autoComplete="off"
            aria-label="Filter folders"
          />
          <button type="button" className="jp-x" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="jp-bc-row">
          <button
            type="button"
            className="jp-up"
            onClick={() => path !== '/' && load(parentPath(path))}
            disabled={path === '/'}
            title="Up one level"
          >
            ↑
          </button>
          <div className="jp-crumblist">
            {crumbs.map((c, i) => (
              <span key={c.target} className="jp-crumb-wrap">
                <button
                  type="button"
                  className={`jp-crumb${i === crumbs.length - 1 ? ' active' : ''}`}
                  onClick={() => load(c.target)}
                >
                  {c.label}
                </button>
                {i < crumbs.length - 1 && <span className="jp-sep">/</span>}
              </span>
            ))}
          </div>
          <button
            type="button"
            className="jp-new"
            onClick={() => setNewFolder({ name: '' })}
            disabled={newFolder !== null}
          >
            + folder
          </button>
        </div>

        {newFolder && (
          <div className="jp-newrow">
            <span className="jp-row-icon">▤</span>
            <input
              autoFocus
              value={newFolder.name}
              onChange={(e) => setNewFolder({ name: e.target.value })}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreate();
                if (e.key === 'Escape') setNewFolder(null);
                e.stopPropagation();
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
          </div>
        )}

        <div className="jp-list" role="listbox">
          {loading && entries.length === 0 ? (
            <>
              <div className="jp-skel" />
              <div className="jp-skel" />
              <div className="jp-skel" />
            </>
          ) : error ? (
            <div className="jp-empty jp-err">{error}</div>
          ) : flat.length === 0 ? (
            <div className="jp-empty">
              {isPathQuery
                ? psLoading
                  ? 'listing…'
                  : 'no matching folders'
                : queryTrim
                  ? 'no matches here'
                  : 'no subfolders — use this folder below'}
            </div>
          ) : (
            sections.map((sec, si) => (
              <div className="jp-section" key={sec.label || `s${si}`}>
                {sec.label && <div className="jp-section-label">{sec.label}</div>}
                {sec.items.map((item) => {
                  running += 1;
                  const idx = running;
                  const fav = item.fav ?? isFavorite(hostId, item.path);
                  return (
                    <Row
                      key={`${sec.label || 's'}-${item.path}`}
                      name={item.name}
                      parent={item.anchor ? item.parent : ''}
                      indices={item.indices}
                      fav={fav}
                      active={idx === hi}
                      onHover={() => setHi(idx)}
                      onOpen={() => load(item.path)}
                      onPin={() => pin(item.path)}
                    />
                  );
                })}
              </div>
            ))
          )}
        </div>

        <div className="jp-selectbar">
          <div className="jp-cur">
            <span className="jp-cur-label">USE</span>
            <code className="jp-cur-path">{display(path)}</code>
          </div>
          <button type="button" className="btn-primary jp-use" onClick={selectCurrent}>
            Select this folder
          </button>
        </div>
      </div>
    </div>
  );

  return typeof document !== 'undefined' ? createPortal(node, document.body) : node;
}

function Row({ name, parent, indices, fav, active, onHover, onOpen, onPin }) {
  return (
    <div
      className={`jp-row ${active ? 'active' : ''}`}
      onMouseEnter={onHover}
      role="option"
      aria-selected={active}
    >
      <button type="button" className="jp-row-main" onClick={onOpen} title={`Open ${name}`}>
        <span className="jp-row-icon">▸</span>
        <span className="jp-row-name">
          <Highlighted text={name} indices={indices} />
        </span>
        {parent ? <span className="jp-row-parent">{parent === '/' ? '' : parent}</span> : null}
        <span className="jp-row-chev">›</span>
      </button>
      <button
        type="button"
        className={`jp-pin ${fav ? 'on' : ''}`}
        onClick={(e) => {
          e.stopPropagation();
          onPin();
        }}
        title={fav ? 'Unpin' : 'Pin to favorites'}
        aria-label={fav ? 'Unpin' : 'Pin'}
      >
        ★
      </button>
    </div>
  );
}

function Highlighted({ text, indices }) {
  if (!indices || indices.length === 0) return text;
  const set = new Set(indices);
  return (
    <>
      {Array.from(text).map((ch, i) =>
        set.has(i) ? (
          <mark key={i} className="jp-hl">
            {ch}
          </mark>
        ) : (
          <span key={i}>{ch}</span>
        ),
      )}
    </>
  );
}
