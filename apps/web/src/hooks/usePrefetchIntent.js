import { useEffect, useRef } from 'react';

// Handlers expressing "the user is about to open this": a short hover
// dwell on desktop (so sweeping the pointer over a list doesn't fire per
// row), and pointerdown on touch — the press that opens the row races the
// prefetch, which is exactly the point.
export function usePrefetchIntent(fire, delayMs = 150) {
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);
  return {
    onMouseEnter: () => {
      clearTimeout(timer.current);
      timer.current = setTimeout(fire, delayMs);
    },
    onMouseLeave: () => clearTimeout(timer.current),
    onPointerDown: fire,
  };
}
