import { useState } from 'react';

// Renders the daemon's edit-tool output as a real diff, the way Claude
// Code / Codex present file changes: per-file header with +N −M counts,
// green/red-tinted lines, dim hunk markers, collapsed beyond a few lines.
//
// Input contract (services/daemon/adapters/items.py::_format_changes):
//   summary — one "verb /path" line per file (in args.command)
//   diff    — single file: the raw unified diff;
//             multiple files: sections prefixed with "--- /path" lines.

const COLLAPSED_LINES = 14;

function parseFiles(summary, diff) {
  const verbs = (summary || '')
    .split('\n')
    .map((line) => {
      const m = /^(\w+)\s+(.+?)(?:\s+→\s+(.+))?$/.exec(line.trim());
      return m ? { verb: m[1], path: m[2], movedTo: m[3] } : null;
    })
    .filter(Boolean);

  const text = diff || '';
  if (verbs.length <= 1) {
    return [{ ...(verbs[0] || { verb: 'update', path: '' }), lines: text.split('\n') }];
  }
  // Multi-file: split on the "--- /path" separators the daemon inserts.
  const files = [];
  let current = null;
  for (const line of text.split('\n')) {
    const head = /^--- (.+)$/.exec(line);
    const known = head && verbs.find((v) => v.path === head[1]);
    if (known) {
      current = { ...known, lines: [] };
      files.push(current);
      continue;
    }
    if (current) current.lines.push(line);
  }
  return files.length ? files : [{ verb: 'update', path: '', lines: text.split('\n') }];
}

function lineClass(line) {
  if (line.startsWith('+++') || line.startsWith('---')) return 'diff-meta';
  if (line.startsWith('@@')) return 'diff-hunk';
  if (line.startsWith('+')) return 'diff-add';
  if (line.startsWith('-')) return 'diff-del';
  return 'diff-ctx';
}

function counts(lines) {
  let add = 0;
  let del = 0;
  for (const line of lines) {
    if (line.startsWith('+') && !line.startsWith('+++')) add += 1;
    else if (line.startsWith('-') && !line.startsWith('---')) del += 1;
  }
  return { add, del };
}

function FileDiff({ file, streaming }) {
  const [expanded, setExpanded] = useState(false);
  const { add, del } = counts(file.lines);
  const overflow = file.lines.length > COLLAPSED_LINES;
  // While streaming show the TAIL (the part being written); when settled
  // show the head.
  const shown = expanded || !overflow
    ? file.lines
    : streaming
      ? file.lines.slice(-COLLAPSED_LINES)
      : file.lines.slice(0, COLLAPSED_LINES);

  return (
    <div className="diff-file">
      <button
        type="button"
        className="diff-file-head"
        onClick={() => overflow && setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <span className="diff-file-verb">{file.verb}</span>
        <span className="diff-file-path" title={file.path}>
          {/* bdi: keep LTR char order inside the RTL tail-truncation */}
          <bdi dir="ltr">
            {file.path}
            {file.movedTo ? ` → ${file.movedTo}` : ''}
          </bdi>
        </span>
        <span className="diff-file-counts">
          {add > 0 && <span className="diff-count-add">+{add}</span>}
          {del > 0 && <span className="diff-count-del">−{del}</span>}
          {overflow && (
            <span className="diff-file-toggle">{expanded ? 'collapse' : 'expand'}</span>
          )}
        </span>
      </button>
      <pre className="diff-body">
        {shown.map((line, i) => (
          <span key={i} className={`diff-line ${lineClass(line)}`}>
            {line || ' '}
            {'\n'}
          </span>
        ))}
        {!expanded && overflow && (
          <span
            className="diff-more"
            role="button"
            tabIndex={0}
            onClick={() => setExpanded(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                setExpanded(true);
              }
            }}
          >
            … {file.lines.length - COLLAPSED_LINES} more lines
          </span>
        )}
      </pre>
    </div>
  );
}

export function DiffView({ summary, diff, streaming = false }) {
  const files = parseFiles(summary, diff);
  return (
    <div className="diff-view">
      {files.map((file, i) => (
        <FileDiff key={`${file.path}-${i}`} file={file} streaming={streaming} />
      ))}
    </div>
  );
}
