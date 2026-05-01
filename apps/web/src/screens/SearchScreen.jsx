import { useEffect, useState } from 'react';
import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';
import { MarkdownText } from '../util/markdown';

const SEARCH_PAGE_SIZE = 50;

const MODES = [
  {
    id: 'hybrid',
    label: 'hybrid',
    headline: 'All three, fused',
    body: 'Runs keyword + semantic + (exact if you quote anything), then merges with Reciprocal Rank Fusion. Best default — covers paraphrase, identifier, and literal matches all at once.',
  },
  {
    id: 'semantic',
    label: 'semantic',
    headline: 'Meaning-based',
    body: 'Qwen3 embeds your query into a 4096-dim vector and ranks chunks by cosine similarity. Finds paraphrases and typos ("dockerize" matches "dokcer compsoe"). Misses literal tokens the model never learned.',
  },
  {
    id: 'bm25',
    label: 'keyword',
    headline: 'Term frequency',
    body: 'Postgres BM25 ranking over the tokenized text. Rewards rare words and clustered matches. Perfect for code identifiers, filenames, acronyms. Supports OR, -negation, "quoted phrases".',
  },
  {
    id: 'exact',
    label: 'exact',
    headline: 'Literal substring',
    body: 'ILIKE match backed by a trigram index. Returns every chunk whose text contains your query verbatim, case-insensitive. No ranking — use this for error strings, paths, exact quotes.',
  },
];

const RERANK_TOOLTIP = {
  headline: 'Cross-encoder rerank',
  body: 'After retrieval, a Qwen3 reranker reads each (query, chunk) pair jointly and emits a relevance score. Adds ~1-2s but substantially improves top-1 quality on ambiguous queries.',
};

const STAGE_LABELS = {
  bm25: 'keyword',
  semantic: 'semantic',
  exact: 'exact',
  rerank: 'rerank',
};

export function SearchScreen({ state, onQueryChange, onSearch, onOpenResult, onModeChange, onRerankChange }) {
  // W5: cap rendered results at SEARCH_PAGE_SIZE; "show more" extends.
  // Keeps the DOM small (and scroll snappy) for very common 100+ result
  // queries without adding a virtualization library.
  const [visibleCount, setVisibleCount] = useState(SEARCH_PAGE_SIZE);
  // Reset the cap whenever the query changes (each new search starts at
  // the page-size top slice).
  useEffect(() => {
    setVisibleCount(SEARCH_PAGE_SIZE);
  }, [state.searchQuery]);
  const totalResults = state.searchResults.length;
  const visibleResults = state.searchResults.slice(0, visibleCount);
  const hiddenResults = totalResults - visibleResults.length;
  const config = state.searchConfig;
  const mode = state.searchMode || 'hybrid';
  const rerank = state.searchRerank || 'auto';
  const rerankAvailable = !!config?.reranker_enabled;

  return (
    <div className="screen search-screen">
      <div className="section-title">chat search</div>

      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          onSearch(state.searchQuery, { mode, rerank });
        }}
      >
        <input
          type="search"
          value={state.searchQuery}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder='Search chats — "quoted" for exact, -term to exclude'
          spellCheck={false}
        />
        <button type="submit" disabled={state.searchLoading}>
          {state.searchLoading ? 'Searching' : 'Search'}
        </button>
      </form>

      <div className="search-selector">
        <div className="search-mode-group" role="radiogroup" aria-label="Search mode">
          {MODES.map((m) => (
            <span key={m.id} className="search-mode-wrap">
              <button
                type="button"
                role="radio"
                aria-checked={mode === m.id}
                className={`search-mode ${mode === m.id ? 'is-active' : ''}`}
                onClick={() => {
                  onModeChange?.(m.id);
                  if (state.searchQuery.trim()) onSearch(state.searchQuery, { mode: m.id, rerank });
                }}
              >
                {m.label}
              </button>
              <ModeTooltip headline={m.headline} body={m.body} />
            </span>
          ))}
        </div>
        {rerankAvailable && (
          <span className="search-rerank-wrap">
            <label className={`search-rerank ${rerank !== 'off' ? 'is-on' : 'is-off'}`}>
              <input
                type="checkbox"
                checked={rerank !== 'off'}
                onChange={(e) => {
                  const next = e.target.checked ? 'auto' : 'off';
                  onRerankChange?.(next);
                  if (state.searchQuery.trim()) onSearch(state.searchQuery, { mode, rerank: next });
                }}
              />
              <span>rerank</span>
            </label>
            <ModeTooltip
              headline={RERANK_TOOLTIP.headline}
              body={RERANK_TOOLTIP.body}
              sub={config?.reranker_model ? `model: ${config.reranker_model}` : null}
            />
          </span>
        )}
      </div>

      <SearchPipeline stages={state.searchStages} origin={state.searchStageOrigin} />

      {config && !config.enabled && (
        <div className="search-config-warning">
          Search storage is {config.storage_enabled ? 'ready' : 'off'}; embeddings are
          {config.api_key_configured ? ' waiting on the API endpoint' : ' missing an API key'}.
        </div>
      )}

      <div className="search-meta">
        {config
          ? `${config.model} · ${config.dimensions} dims`
          : 'loading search config'}
        {rerankAvailable && ` · rerank: ${config.reranker_model}`}
      </div>

      {state.searchLoading && state.searchResults.length === 0 ? (
        <div className="empty">searching...</div>
      ) : state.searchResults.length === 0 ? (
        <div className="empty">enter a query to find prior chats</div>
      ) : (
        <div className="search-results">
          {visibleResults.map((result, idx) => (
            <button
              type="button"
              className="search-result"
              key={result.id}
              style={{ order: idx }}
              onClick={() => onOpenResult(result)}
            >
              <div className="search-result-header">
                <span className={`search-role-chip search-role-${result.role}`}>
                  {roleLabel(result.role)}
                </span>
                {result.signals?.length > 0 && (
                  <span className="search-signals">{result.signals.join(' + ')}</span>
                )}
                <span className="search-result-score">{scoreLabel(result)}</span>
              </div>
              <div className="search-snippet">
                <MarkdownText
                  text={stripRolePrefix(pickDisplayText(result))}
                  className="search-md"
                />
              </div>
              <div className="search-result-meta">
                <span>{relativeAge(result.created_at)}</span>
                {result.cwd && <span>{shortenCwd(result.cwd)}</span>}
                {result.thread_id && <span>thread {result.thread_id.slice(0, 8)}…</span>}
              </div>
            </button>
          ))}
          {hiddenResults > 0 && (
            <button
              type="button"
              className="search-show-more"
              onClick={() => setVisibleCount((n) => n + SEARCH_PAGE_SIZE)}
            >
              Show {Math.min(SEARCH_PAGE_SIZE, hiddenResults)} more · {hiddenResults} hidden
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ModeTooltip({ headline, body, sub }) {
  return (
    <span className="mode-tooltip" role="tooltip">
      <span className="mode-tooltip-headline">{headline}</span>
      <span className="mode-tooltip-body">{body}</span>
      {sub && <span className="mode-tooltip-sub">{sub}</span>}
    </span>
  );
}

function SearchPipeline({ stages, origin }) {
  if (!stages || stages.length === 0) return null;
  return (
    <div className="search-pipeline" aria-label="search pipeline">
      {stages.map((stage) => (
        <div
          key={stage.name}
          className={`pipeline-stage is-${stage.status} ${origin === stage.name ? 'is-origin' : ''}`}
        >
          <span className="pipeline-dot" />
          <span className="pipeline-label">{STAGE_LABELS[stage.name] || stage.name}</span>
          <span className="pipeline-detail">
            {stage.status === 'pending' && '…'}
            {stage.status === 'running' && `running${stage.count ? ` ·${stage.count}` : ''}`}
            {stage.status === 'done' && (
              <>
                {typeof stage.elapsed_ms === 'number' ? `${stage.elapsed_ms}ms` : 'done'}
                {typeof stage.count === 'number' ? ` ·${stage.count}` : ''}
              </>
            )}
            {stage.status === 'error' && 'error'}
          </span>
        </div>
      ))}
    </div>
  );
}

function scoreLabel(result) {
  if (typeof result.score !== 'number') return 'score n/a';
  if (result.signal === 'semantic' || (result.signals && result.signals.length === 1 && result.signals[0] === 'semantic')) {
    return `${Math.round(result.score * 100)}%`;
  }
  return result.score.toFixed(3);
}

// Each chunk's text starts with "User: ", "Codex: ", or "Reasoning: " so
// the same prefix would otherwise show up in every search snippet. The
// role is already surfaced as a chip, so strip the leading label here —
// cover both plain ("User: ") and BM25-highlighted ("«User»: ") forms,
// and the headline-fragment case where "…User: …" leads with an ellipsis.
const ROLE_PREFIX_RE = /^[…\s]*«?(User|Codex|Reasoning)»?\s*:\s*/i;

function stripRolePrefix(text) {
  if (!text) return '';
  return text.replace(ROLE_PREFIX_RE, '');
}

function roleLabel(role) {
  if (role === 'assistant') return 'codex';
  if (role === 'reasoning') return 'reasoning';
  if (role === 'user') return 'you';
  return role || '—';
}

// Pick the best body to render. BM25/exact highlights are short fragments
// with match markers — use those when present. Otherwise fall back to the
// full chunk text: `snippet` collapses newlines to spaces which breaks
// markdown (a line starting with ``` becomes a zero-body code fence),
// and chunks are capped at ~2k chars so rendering the full text is fine.
function pickDisplayText(result) {
  if (result.highlight) return result.highlight;
  if (result.text) return result.text;
  return result.snippet || '';
}
