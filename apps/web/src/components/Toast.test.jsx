import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Toast } from './Toast';

describe('Toast accessibility', () => {
  it('announces errors and exposes a real dismiss button', () => {
    const html = renderToStaticMarkup(
      <Toast message="connection failed" tone="error" onDismiss={() => {}} />,
    );
    expect(html).toContain('role="alert"');
    expect(html).toContain('aria-live="assertive"');
    expect(html).toContain('<button');
    expect(html).toContain('aria-label="Dismiss notification"');
  });

  it('uses polite status semantics for informational feedback', () => {
    const html = renderToStaticMarkup(
      <Toast message="/plan ok" tone="info" onDismiss={() => {}} />,
    );
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
  });
});
