import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PendingPromptsPanel, approvalActions } from './PendingPromptsPanel';

const noop = () => {};

function renderApproval(decisions) {
  return renderToStaticMarkup(
    <PendingPromptsPanel
      approval={{ approvalId: 'a1', kind: 'command', decisions }}
      userInput={null}
      onApprovalDecision={noop}
      onUserInputSubmit={noop}
      onUserInputCancel={noop}
    />,
  );
}

describe('PendingPromptsPanel approvals', () => {
  it('treats the supplied decisions as authoritative, including cancel', () => {
    expect(approvalActions(['cancel']).map((action) => action.decision)).toEqual(['cancel']);
    const html = renderApproval(['cancel']);
    expect(html).toMatch(/>cancel<\/button>/);
    expect(html).not.toMatch(/>accept<\/button>/);
    expect(html).not.toMatch(/>decline<\/button>/);
    expect(html).not.toMatch(/>always<\/button>/);
  });

  it('falls back to every supported decision when the list is absent or empty', () => {
    for (const decisions of [undefined, []]) {
      expect(approvalActions(decisions).map((action) => action.decision)).toEqual([
        'decline',
        'cancel',
        'acceptForSession',
        'accept',
      ]);
      const html = renderApproval(decisions);
      for (const label of ['decline', 'cancel', 'always', 'accept']) {
        expect(html).toMatch(new RegExp(`>${label}<\\/button>`));
      }
    }
  });
});

describe('PendingPromptsPanel user input', () => {
  it('masks secret answers with a password input', () => {
    const html = renderToStaticMarkup(
      <PendingPromptsPanel
        approval={null}
        userInput={{
          callId: 'secret-call',
          questions: [{
            id: 'token',
            header: 'Access token',
            question: 'Enter the token',
            isSecret: true,
            options: [],
          }],
        }}
        onApprovalDecision={noop}
        onUserInputSubmit={noop}
        onUserInputCancel={noop}
      />,
    );
    expect(html).toContain('type="password"');
    expect(html).toContain('autoComplete="new-password"');
    expect(html).not.toContain('<textarea');
  });
});
