import { useId } from 'react';

// Telemetry chart SVG with subtle grid, layered area fill, and endpoint
// markers. The caller owns the metric semantics; this component only
// handles scaling + drawing.

export function Sparkline({
  points = [],
  secondaryPoints = [],
  max = null,
  color = 'var(--accent)',
  secondaryColor = 'var(--accent-pink)',
  fill = null,
  secondaryFill = null,
  height = 72,
  strokeWidth = 1.7,
  className = '',
}) {
  const gradientId = useId().replace(/:/g, '');
  const width = 100; // viewBox width — SVG scales to container via CSS
  const h = height;
  const padX = 2;
  const padY = 7;
  const primary = points.length ? points : [0, 0];
  const secondary = secondaryPoints.length ? secondaryPoints : null;
  const cap = max ?? Math.max(1, ...primary, ...(secondary || []));
  const plotW = width - padX * 2;
  const plotH = h - padY * 2;
  const guides = [0.15, 0.4, 0.65, 0.9].map((ratio) => padY + plotH * ratio);
  const rails = [0.18, 0.5, 0.82].map((ratio) => padX + plotW * ratio);

  function buildSeries(series) {
    const stepX = series.length > 1 ? plotW / (series.length - 1) : 0;
    const coords = series.map((v, i) => {
      const x = padX + i * stepX;
      const clamped = Math.max(0, Math.min(cap, v));
      const y = h - padY - (clamped / cap) * plotH;
      return [x, y];
    });
    const linePath = coords
      .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`)
      .join(' ');
    const areaPath = coords.length
      ? `${linePath} L${coords[coords.length - 1][0].toFixed(2)},${(h - padY).toFixed(2)} L${padX.toFixed(2)},${(h - padY).toFixed(2)} Z`
      : '';
    return {
      linePath,
      areaPath,
      last: coords[coords.length - 1],
    };
  }

  const primarySeries = buildSeries(primary);
  const secondarySeries = secondary ? buildSeries(secondary) : null;
  const fillColor = fill || color;
  const secondaryFillColor = secondaryFill || secondaryColor;

  return (
    <svg
      className={`sparkline ${className}`}
      viewBox={`0 0 ${width} ${h}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={`${gradientId}-primary`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={fillColor} stopOpacity="0.28" />
          <stop offset="100%" stopColor={fillColor} stopOpacity="0.03" />
        </linearGradient>
        <linearGradient id={`${gradientId}-secondary`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={secondaryFillColor} stopOpacity="0.18" />
          <stop offset="100%" stopColor={secondaryFillColor} stopOpacity="0.02" />
        </linearGradient>
      </defs>

      <rect
        x={padX}
        y={padY}
        width={plotW}
        height={plotH}
        fill="rgba(255,255,255,0.015)"
        stroke="rgba(136,164,196,0.09)"
        strokeWidth="0.45"
      />
      {guides.map((y) => (
        <line
          key={`guide-${y}`}
          x1={padX}
          x2={width - padX}
          y1={y}
          y2={y}
          stroke="rgba(136,164,196,0.12)"
          strokeWidth="0.45"
          vectorEffect="non-scaling-stroke"
        />
      ))}
      {rails.map((x) => (
        <line
          key={`rail-${x}`}
          x1={x}
          x2={x}
          y1={padY}
          y2={h - padY}
          stroke="rgba(136,164,196,0.08)"
          strokeWidth="0.45"
          vectorEffect="non-scaling-stroke"
        />
      ))}

      {secondarySeries?.areaPath && (
        <path
          d={secondarySeries.areaPath}
          fill={`url(#${gradientId}-secondary)`}
          stroke="none"
        />
      )}
      {primarySeries.areaPath && (
        <path
          d={primarySeries.areaPath}
          fill={`url(#${gradientId}-primary)`}
          stroke="none"
        />
      )}
      {secondarySeries?.linePath && (
        <path
          d={secondarySeries.linePath}
          fill="none"
          stroke={secondaryColor}
          strokeWidth={Math.max(1.2, strokeWidth - 0.15)}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
      )}
      <path
        d={primarySeries.linePath}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      {secondarySeries?.last && (
        <circle
          cx={secondarySeries.last[0]}
          cy={secondarySeries.last[1]}
          r="1.9"
          fill={secondaryColor}
          stroke="rgba(5,9,16,0.9)"
          strokeWidth="0.9"
        />
      )}
      {primarySeries.last && (
        <circle
          cx={primarySeries.last[0]}
          cy={primarySeries.last[1]}
          r="2.2"
          fill={color}
          stroke="rgba(5,9,16,0.95)"
          strokeWidth="0.95"
        />
      )}
    </svg>
  );
}
