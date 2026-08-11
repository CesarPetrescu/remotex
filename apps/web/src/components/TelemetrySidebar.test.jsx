import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TelemetrySidebar, telemetryFreshness } from './TelemetrySidebar';

describe('TelemetrySidebar', () => {
  it('ages a silent online host from live to stale', () => {
    expect(telemetryFreshness(1_000, true, 11_000)).toMatchObject({
      live: true,
      label: 'Live',
    });
    expect(telemetryFreshness(1_000, true, 12_001)).toMatchObject({
      live: false,
      label: 'Stale',
    });
    expect(telemetryFreshness(1_000, false, 12_001).label).toBe('Offline');
    expect(telemetryFreshness(1_000, false, 2_000).label).toBe('Offline');
  });

  it('renders every reported GPU', () => {
    const gpu = (name, percent) => ({
      name,
      percent,
      mem_used_mb: 4096,
      mem_total_mb: 8192,
      temp_c: 55,
    });
    const html = renderToStaticMarkup(
      <TelemetrySidebar
        selectedHost={{ nickname: 'workstation', online: true }}
        telemetry={{
          current: { gpus: [gpu('GPU A', 10), gpu('GPU B', 20)] },
          history: {
            cpu: [],
            mem: [],
            gpu: [{ t: 1, v: 10 }],
            gpus: [[{ t: 1, v: 10 }], [{ t: 1, v: 20 }]],
            up: [],
            down: [],
          },
          lastUpdate: Date.now(),
        }}
      />,
    );

    expect(html).toContain('GPU 1');
    expect(html).toContain('GPU 2');
    expect(html).toContain('GPU A');
    expect(html).toContain('GPU B');
  });
});
