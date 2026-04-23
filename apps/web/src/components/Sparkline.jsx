// Lightweight SVG sparkline — no deps. Expects a points array; the
// caller owns normalization. The path auto-scales to the component's
// width/height and renders both a filled area and a stroke.

export function Sparkline({
  points = [],
  max = null,
  color = 'var(--accent)',
  fill = null,
  height = 56,
  strokeWidth = 1.5,
  className = '',
}) {
  const width = 100; // viewBox width — SVG scales to container via CSS
  const h = height;
  const safe = points.length ? points : [0, 0];
  const cap = max ?? Math.max(1, ...safe);
  const stepX = safe.length > 1 ? width / (safe.length - 1) : 0;
  const coords = safe.map((v, i) => {
    const x = i * stepX;
    const clamped = Math.max(0, Math.min(cap, v));
    const y = h - (clamped / cap) * (h - 2) - 1;
    return [x, y];
  });
  const linePath = coords
    .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`)
    .join(' ');
  const areaPath = coords.length
    ? `${linePath} L${(coords[coords.length - 1][0]).toFixed(2)},${h} L0,${h} Z`
    : '';
  const fillColor = fill || color;
  return (
    <svg
      className={`sparkline ${className}`}
      viewBox={`0 0 ${width} ${h}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {areaPath && (
        <path d={areaPath} fill={fillColor} opacity="0.18" stroke="none" />
      )}
      <path
        d={linePath}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}
