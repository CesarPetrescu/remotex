import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SendOrStopButton } from './SendOrStopButton';

const render = (props) => renderToStaticMarkup(<SendOrStopButton {...props} />);
const noop = () => {};
const base = { onSend: noop, onSteer: noop, onQueue: noop };

describe('submit control', () => {
  it('is one button when idle', () => {
    const html = render({ ...base, pending: false, canSend: true });
    expect(html.match(/<button/g)).toHaveLength(1);
    expect(html).toContain('aria-label="Send"');
    expect(html).not.toContain('send-alt');
  });

  it('disables itself with nothing to send', () => {
    expect(render({ ...base, pending: false, canSend: false })).toContain('disabled');
  });

  it('grows a chevron only while steering a running turn', () => {
    const steering = render({
      ...base, pending: true, canSend: true, canSteer: true, canQueue: true,
    });
    expect(steering.match(/<button/g)).toHaveLength(2);
    expect(steering).toContain('send-alt');
    expect(steering).toContain('aria-label="Steer current turn"');
  });

  it('omits the chevron when queueing is unavailable', () => {
    const html = render({
      ...base, pending: true, canSend: true, canSteer: true, canQueue: false,
    });
    expect(html.match(/<button/g)).toHaveLength(1);
    expect(html).not.toContain('send-alt');
  });

  // The whole point of the redesign: no red abort target next to send. Stop
  // lives on the transcript's Working row instead.
  it('never renders a stop control', () => {
    for (const pending of [true, false]) {
      for (const canSteer of [true, false]) {
        const html = render({ ...base, pending, canSend: true, canSteer, canQueue: true });
        expect(html).not.toContain('send-stop stop');
        expect(html).not.toContain('aria-label="Stop"');
      }
    }
  });

  // The primary must not move or change size between states, or you can never
  // learn where it is.
  it('keeps the primary first and identically classed in every state', () => {
    const idle = render({ ...base, pending: false, canSend: true });
    const steer = render({
      ...base, pending: true, canSend: true, canSteer: true, canQueue: true,
    });
    for (const html of [idle, steer]) {
      expect(html.indexOf('send-stop send')).toBeLessThan(
        html.indexOf('send-alt') === -1 ? Infinity : html.indexOf('send-alt'),
      );
      expect(html).toContain('↑');
    }
  });
});
