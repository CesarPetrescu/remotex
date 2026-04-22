import { relativeAge } from '../util/time';
import { shortenCwd } from '../util/path';

export function SearchScreen({ state, onQueryChange, onSearch, onOpenResult }) {
  const config = state.searchConfig;
  return (
    <div className="screen search-screen">
      <div className="section-title">semantic chat search</div>
      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          onSearch(state.searchQuery);
        }}
      >
        <input
          type="search"
          value={state.searchQuery}
          onChange={(e) => onQueryChange(e.target.value)}
          placeholder="Search chats, answers, and reasoning"
          spellCheck={false}
        />
        <button type="submit" disabled={state.searchLoading}>
          {state.searchLoading ? 'Searching' : 'Search'}
        </button>
      </form>
      {config && !config.enabled && (
        <div className="search-config-warning">
          Search storage is {config.storage_enabled ? 'ready' : 'off'}; embeddings are
          {config.api_key_configured ? ' waiting on the API endpoint' : ' missing an API key'}.
        </div>
      )}
      <div className="search-meta">
        {config
          ? `${config.model} · ${config.dimensions} dims · ${config.max_context_tokens} ctx`
          : 'loading search config'}
      </div>
      {state.searchLoading && state.searchResults.length === 0 ? (
        <div className="empty">searching...</div>
      ) : state.searchResults.length === 0 ? (
        <div className="empty">enter a query to find prior chats</div>
      ) : (
        <div className="search-results">
          {state.searchResults.map((result) => (
            <button
              type="button"
              className="search-result"
              key={result.id}
              onClick={() => onOpenResult(result)}
            >
              <div className="search-snippet">{result.snippet || result.text}</div>
              <div className="search-result-meta">
                <span>{result.role}</span>
                <span>{scoreLabel(result.score)}</span>
                <span>{relativeAge(result.created_at)}</span>
                {result.cwd && <span>{shortenCwd(result.cwd)}</span>}
                {result.thread_id && <span>{result.thread_id.slice(0, 8)}...</span>}
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function scoreLabel(score) {
  if (typeof score !== 'number') return 'score n/a';
  return `${Math.round(score * 100)}%`;
}
