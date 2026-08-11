import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SessionUsage } from './SessionScreen';

describe('SessionUsage', () => {
  it('renders normalized token counters and goal budget progress', () => {
    const html = renderToStaticMarkup(
      <SessionUsage
        goal={{
          objective: 'finish parity',
          status: 'budgetLimited',
          tokens_used: 250,
          token_budget: 1_000,
        }}
        tokensInput={1_200}
        tokensOutput={300}
        tokensCached={800}
        tokensReasoning={50}
      />,
    );

    expect(html).toContain('goal budget limited');
    expect(html).toContain('25%');
    expect(html).toContain('aria-label="Goal token budget"');
    expect(html).toContain('tokens 1.2k in · 300 out · 800 cached · 50 reasoning');
  });

  it('stays absent until Codex reports a goal or usage', () => {
    expect(renderToStaticMarkup(<SessionUsage goal={null} />)).toBe('');
  });
});
