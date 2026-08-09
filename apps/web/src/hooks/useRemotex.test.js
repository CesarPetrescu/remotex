import { describe, expect, it } from 'vitest';
import {
  attachedTurnInFlight,
  dequeuePrompt,
  enqueuePrompt,
  initialState,
  reconcileQueue,
  reducer,
} from './useRemotex';

describe('attached turn state', () => {
  it('uses the relay snapshot and preserves state for older relays', () => {
    expect(attachedTurnInFlight({ turn_in_flight: true })).toBe(true);
    expect(attachedTurnInFlight({ turn_in_flight: false })).toBe(false);
    expect(attachedTurnInFlight({})).toBeNull();
  });
});

const approval = (id, extra = {}) => ({ approvalId: id, decisions: ['accept', 'decline'], ...extra });
const userInput = (id, extra = {}) => ({ callId: id, questions: [], ...extra });

const run = (actions, from = initialState) => actions.reduce(reducer, from);

describe('prompt queue helpers', () => {
  const keyOf = (p) => p.approvalId;

  it('appends new prompts and replaces duplicates in place', () => {
    let q = enqueuePrompt([], approval('a'), keyOf);
    q = enqueuePrompt(q, approval('b'), keyOf);
    q = enqueuePrompt(q, approval('a', { reason: 'replayed' }), keyOf);
    expect(q.map(keyOf)).toEqual(['a', 'b']);
    expect(q[0].reason).toBe('replayed');
  });

  it('dequeues by id, and the head when no id is given', () => {
    const q = [approval('a'), approval('b')];
    expect(dequeuePrompt(q, 'b', keyOf).map(keyOf)).toEqual(['a']);
    expect(dequeuePrompt(q, undefined, keyOf).map(keyOf)).toEqual(['b']);
  });

  it('reconciles to the snapshot membership while keeping local order', () => {
    const local = [approval('a'), approval('b')];
    // A peer answered "a"; the relay also knows about a newer "c".
    // Neither entry carries the relay's `order`, so local order stands.
    const merged = reconcileQueue(local, [approval('c'), approval('b')], keyOf);
    expect(merged.map(keyOf)).toEqual(['b', 'c']);
  });

  it('follows the relay order when the snapshot carries one', () => {
    // The relay handed back a claim it could not forward: "a" is older
    // than "b" and must land back in front of it, not at the tail.
    const local = [approval('b', { order: 2 })];
    const merged = reconcileQueue(
      local,
      [approval('b', { order: 2 }), approval('a', { order: 1 })],
      keyOf,
    );
    expect(merged.map(keyOf)).toEqual(['a', 'b']);
  });
});

describe('reducer: pending approval queue (contract F)', () => {
  it('does not let a second approval evict the first', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a2') },
    ]);
    expect(s.pendingApprovals.map((p) => p.approvalId)).toEqual(['a1', 'a2']);
    expect(s.pendingApproval.approvalId).toBe('a1');
  });

  it('reveals the next prompt when the head is resolved', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a2') },
      { type: 'APPROVAL_CLEAR', approvalId: 'a1' },
    ]);
    expect(s.pendingApproval.approvalId).toBe('a2');
    expect(s.pendingApprovals).toHaveLength(1);
  });

  it('resolving a non-head prompt leaves the head on screen', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a2') },
      { type: 'APPROVAL_CLEAR', approvalId: 'a2' },
    ]);
    expect(s.pendingApprovals.map((p) => p.approvalId)).toEqual(['a1']);
    expect(s.pendingApproval.approvalId).toBe('a1');
  });

  it('ignores a replayed duplicate of a queued prompt', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
    ]);
    expect(s.pendingApprovals).toHaveLength(1);
  });

  it('empties the queue when a prompt-less snapshot arrives', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'PENDING_PROMPTS', approvals: [], userInputs: [] },
    ]);
    expect(s.pendingApprovals).toEqual([]);
    expect(s.pendingApproval).toBeNull();
  });

  it('drops only the prompt a peer answered when a snapshot arrives', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a2') },
      { type: 'PENDING_PROMPTS', approvals: [approval('a2')], userInputs: [] },
    ]);
    expect(s.pendingApprovals.map((p) => p.approvalId)).toEqual(['a2']);
    expect(s.pendingApproval.approvalId).toBe('a2');
  });

  it('restores a prompt the relay re-pushes after a failed delivery', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      // optimistic pop on send…
      { type: 'APPROVAL_CLEAR', approvalId: 'a1' },
      // …relay could not reach the host and re-pushes the snapshot.
      { type: 'PENDING_PROMPTS', approvals: [approval('a1')], userInputs: [] },
    ]);
    expect(s.pendingApproval.approvalId).toBe('a1');
  });

  it('clears both queues on session reset', () => {
    const s = run([
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'USER_INPUT_REQUEST', prompt: userInput('c1') },
      { type: 'SESSION_RESET' },
    ]);
    expect(s.pendingApprovals).toEqual([]);
    expect(s.pendingUserInputs).toEqual([]);
    expect(s.pendingApproval).toBeNull();
    expect(s.pendingUserInput).toBeNull();
  });
});

describe('reducer: pending user-input queue (contract F)', () => {
  it('queues concurrent user-input requests independently of approvals', () => {
    const s = run([
      { type: 'USER_INPUT_REQUEST', prompt: userInput('c1') },
      { type: 'APPROVAL_REQUEST', prompt: approval('a1') },
      { type: 'USER_INPUT_REQUEST', prompt: userInput('c2') },
    ]);
    expect(s.pendingUserInputs.map((p) => p.callId)).toEqual(['c1', 'c2']);
    expect(s.pendingUserInput.callId).toBe('c1');
    expect(s.pendingApproval.approvalId).toBe('a1');
  });

  it('reveals the next question set when the head is answered', () => {
    const s = run([
      { type: 'USER_INPUT_REQUEST', prompt: userInput('c1') },
      { type: 'USER_INPUT_REQUEST', prompt: userInput('c2') },
      { type: 'USER_INPUT_CLEAR', callId: 'c1' },
    ]);
    expect(s.pendingUserInput.callId).toBe('c2');
  });
});

describe('reducer: model options', () => {
  const options = [
    { id: '', label: 'default', hint: '', efforts: ['', 'low', 'high'] },
    { id: 'gpt-x', label: 'gpt-x', hint: '', efforts: ['', 'low'] },
  ];

  it('keeps a model the host still offers', () => {
    const s = run([
      { type: 'MODEL_OPTIONS', options },
      { type: 'SET_MODEL', model: 'gpt-x' },
      { type: 'MODEL_OPTIONS', options },
    ]);
    expect(s.model).toBe('gpt-x');
  });

  it('falls back to the default model when the host does not offer it', () => {
    const s = run([
      { type: 'MODEL_OPTIONS', options },
      { type: 'SET_MODEL', model: 'gpt-x' },
      { type: 'MODEL_OPTIONS', options: [options[0]] },
    ]);
    expect(s.model).toBe('');
  });

  it('drops an effort the selected model does not accept', () => {
    const s = run([
      { type: 'MODEL_OPTIONS', options },
      { type: 'SET_MODEL', model: 'gpt-x' },
      { type: 'SET_EFFORT', effort: 'low' },
      { type: 'MODEL_OPTIONS', options: [options[0], { ...options[1], efforts: [''] }] },
    ]);
    expect(s.effort).toBe('');
  });
});

describe('reducer: token storage preference', () => {
  it('defaults to remembering the token', () => {
    expect(initialState.rememberToken).toBe(true);
  });

  it('records an explicit opt-out alongside the token', () => {
    const s = run([{ type: 'SET_TOKEN', token: 'tok-1', remember: false }]);
    expect(s.userToken).toBe('tok-1');
    expect(s.rememberToken).toBe(false);
  });

  it('leaves the preference alone when the token is set without one', () => {
    const s = run([
      { type: 'SET_REMEMBER_TOKEN', remember: false },
      { type: 'SET_TOKEN', token: 'tok-2' },
    ]);
    expect(s.rememberToken).toBe(false);
  });
});
