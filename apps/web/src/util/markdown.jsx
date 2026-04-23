// Minimal markdown renderer that matches the Android (Compose) one.
// Handles fenced code blocks, headings, bold/italic/inline code, and
// bullet lists — the subset codex actually emits. `balanceMarkdown`
// is Ember's trick: while text is still streaming, close dangling
// `**`, `` ` ``, and triple-backtick fences so formatting develops
// live instead of popping when the closer finally arrives.

import { Fragment } from 'react';
import hljs from 'highlight.js/lib/common';
import wasm from 'highlight.js/lib/languages/wasm';
import { CopyButton } from '../components/CopyButton';

// Codex often emits WebAssembly Text — register it under both names.
// `common` covers js/ts/python/rust/go/java/c/cpp/css/html/json/xml/bash/
// sql/yaml/markdown/diff/etc, which already matches everything else we see.
hljs.registerLanguage('wat', wasm);
hljs.registerLanguage('wasm', wasm);

function highlightCode(code, lang) {
  if (!code) return '';
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    } catch { /* fall through to auto */ }
  }
  try {
    return hljs.highlightAuto(code).value;
  } catch { /* fall through to escape */ }
  return code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/**
 * Close trailing unclosed tokens so partial markdown renders cleanly
 * while streaming. Same rules as Android's balanceMarkdown().
 */
export function balanceMarkdown(s) {
  const lines = s.split('\n');
  const openFences =
    lines.filter((l) => l.trimStart().startsWith('```')).length % 2 === 1;
  if (openFences) lines.push('```');
  let last = lines[lines.length - 1];
  const singleTicks = last.replace(/```/g, '');
  if ((singleTicks.match(/`/g) || []).length % 2 === 1) last += '`';
  const boldMatches = last.match(/\*\*/g) || [];
  if (boldMatches.length % 2 === 1) last += '**';
  lines[lines.length - 1] = last;
  return lines.join('\n');
}

export function MarkdownText({ text, className, trailingCursor = false }) {
  const source = trailingCursor ? balanceMarkdown(text || '') : text || '';
  const blocks = parseBlocks(source);
  return (
    <div className={`md ${className || ''}`}>
      {blocks.map((b, i) => (
        <Block key={i} block={b} isLast={i === blocks.length - 1} trailingCursor={trailingCursor} />
      ))}
    </div>
  );
}

function Block({ block, isLast, trailingCursor }) {
  switch (block.type) {
    case 'code': {
      const html = highlightCode(block.content, block.lang);
      return (
        <pre className={`md-code ${block.lang ? `md-lang-${block.lang}` : ''}`}>
          {block.lang && <span className="md-code-lang">{block.lang}</span>}
          <CopyButton getText={() => block.content} />
          <code
            className={`hljs ${block.lang ? `language-${block.lang}` : ''}`}
            dangerouslySetInnerHTML={{ __html: html }}
          />
        </pre>
      );
    }
    case 'heading':
      return <div className={`md-h md-h${block.level}`}>{inlineFormat(block.content)}</div>;
    case 'list':
      return (
        <ul className="md-list">
          {block.items.map((item, i) => (
            <li key={i}>{inlineFormat(item)}</li>
          ))}
        </ul>
      );
    case 'paragraph':
    default:
      return (
        <p className="md-p">
          {inlineFormat(block.content)}
          {isLast && trailingCursor && <span className="md-cursor">▍</span>}
        </p>
      );
  }
}

// ---- block parser ----

function parseBlocks(text) {
  const lines = text.split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trimStart().startsWith('```')) {
      const lang = line.trimStart().slice(3).trim().toLowerCase() || null;
      const buf = [];
      i++;
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        buf.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++;
      const content = buf.join('\n');
      // A fence with no body (common when a one-line snippet starts with
      // ``` because whitespace got collapsed) would otherwise render as an
      // empty <pre> box. Skip it — there's nothing to show anyway.
      if (content.trim()) {
        out.push({ type: 'code', content, lang });
      }
      continue;
    }
    const h = /^(#{1,6})\s+(.*)/.exec(line);
    if (h) {
      out.push({ type: 'heading', level: h[1].length, content: h[2] });
      i++;
      continue;
    }
    if (isBullet(line)) {
      const items = [];
      while (i < lines.length && isBullet(lines[i])) {
        items.push(stripBullet(lines[i]));
        i++;
      }
      out.push({ type: 'list', items });
      continue;
    }
    if (line.trim() === '') {
      i++;
      continue;
    }
    const buf = [line];
    i++;
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !isBullet(lines[i]) &&
      !lines[i].trimStart().startsWith('```') &&
      !/^#{1,6}\s/.test(lines[i])
    ) {
      buf.push(lines[i]);
      i++;
    }
    out.push({ type: 'paragraph', content: buf.join('\n') });
  }
  return out;
}

function isBullet(line) {
  const t = line.trimStart();
  return t.startsWith('- ') || t.startsWith('* ') || /^\d+\.\s/.test(t);
}

function stripBullet(line) {
  const t = line.trimStart();
  if (t.startsWith('- ') || t.startsWith('* ')) return t.slice(2);
  const m = /^\d+\.\s(.*)/.exec(t);
  return m ? m[1] : t;
}

// ---- inline formatter ----

function inlineFormat(text) {
  const out = [];
  let i = 0;
  let key = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === '`') {
      const end = text.indexOf('`', i + 1);
      if (end !== -1) {
        out.push(<code key={key++} className="md-inline-code">{text.slice(i + 1, end)}</code>);
        i = end + 1;
        continue;
      }
    }
    if (c === '*' && text[i + 1] === '*') {
      const end = text.indexOf('**', i + 2);
      if (end !== -1) {
        out.push(<strong key={key++}>{text.slice(i + 2, end)}</strong>);
        i = end + 2;
        continue;
      }
    }
    if ((c === '*' || c === '_') && text[i + 1] !== c) {
      const end = text.indexOf(c, i + 1);
      if (end !== -1 && end > i + 1) {
        out.push(<em key={key++}>{text.slice(i + 1, end)}</em>);
        i = end + 1;
        continue;
      }
    }
    // Search highlight sentinel. The relay wraps matched terms in « » so
    // they survive through the markdown renderer; they never appear in
    // normal Codex output, so the passthrough here is safe everywhere.
    if (c === '«') {
      const end = text.indexOf('»', i + 1);
      if (end !== -1) {
        out.push(
          <mark key={key++} className="md-hl">
            {text.slice(i + 1, end)}
          </mark>,
        );
        i = end + 1;
        continue;
      }
    }
    let j = i;
    while (
      j < text.length &&
      text[j] !== '`' &&
      text[j] !== '*' &&
      text[j] !== '_' &&
      text[j] !== '«'
    ) j++;
    if (j > i) {
      out.push(<Fragment key={key++}>{text.slice(i, j)}</Fragment>);
      i = j;
    } else {
      out.push(<Fragment key={key++}>{text[i]}</Fragment>);
      i++;
    }
  }
  return out;
}
