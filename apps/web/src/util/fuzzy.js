// Tiny subsequence fuzzy matcher used by the Jump folder picker.
// Returns { score, indices } when every char of `query` appears in `target`
// in order, or null otherwise. Higher score = better match. The scorer
// rewards contiguous runs, matches at word boundaries (/ _ - . space), and
// matches at the very start — the same heuristics editors use for Quick Open.
export function fuzzyMatch(query, target) {
  if (!query) return { score: 0, indices: [] };
  const q = query.toLowerCase();
  const t = (target || '').toLowerCase();
  const indices = [];
  let qi = 0;
  let score = 0;
  let prevIdx = -2;
  let streak = 0;

  for (let ti = 0; ti < t.length && qi < q.length; ti++) {
    if (t[ti] !== q[qi]) continue;
    indices.push(ti);
    let pts = 1;
    if (ti === prevIdx + 1) {
      streak += 1;
      pts += streak * 2; // contiguous run bonus
    } else {
      streak = 0;
    }
    const prevCh = ti > 0 ? t[ti - 1] : '/';
    if ('/._- '.includes(prevCh)) pts += 3; // word-boundary bonus
    if (ti === 0) pts += 2; // start bonus
    score += pts;
    prevIdx = ti;
    qi += 1;
  }

  if (qi < q.length) return null; // not all chars consumed → no match
  score -= t.length * 0.05; // gently prefer shorter, less-noisy targets
  return { score, indices };
}
