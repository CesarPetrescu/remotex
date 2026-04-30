import { useEffect, useState } from 'react';

export function ResumingBanner({ sinceMs }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const elapsed = Math.max(0, Math.floor((now - sinceMs) / 1000));
  return (
    <div className="resuming-banner">
      <span className="resuming-dot" />
      <span className="resuming-text">
        Resuming saved chat — codex is reading the rollout
      </span>
      <span className="resuming-elapsed">{elapsed}s</span>
    </div>
  );
}
