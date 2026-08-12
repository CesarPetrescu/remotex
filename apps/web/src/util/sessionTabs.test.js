import { describe, it, expect } from 'vitest';
import { focusTab, openTab, closeTab, threadTabTitle } from './sessionTabs';

const tab = (key) => ({ key, threadId: key, hostId: 'h1', cwd: null, title: key });

describe('sessionTabs', () => {
  it('opens new tabs and keeps them visible up to the cap', () => {
    let s = { tabs: [], shown: [] };
    for (const k of ['a', 'b', 'c']) s = openTab(s.tabs, s.shown, tab(k), 3);
    expect(s.tabs.map((t) => t.key)).toEqual(['a', 'b', 'c']);
    expect(s.shown).toEqual(['c', 'b', 'a']);
  });

  it('evicts the least-recently-used pane past the cap, keeping the tab open', () => {
    let s = { tabs: [], shown: [] };
    for (const k of ['a', 'b', 'c', 'd']) s = openTab(s.tabs, s.shown, tab(k), 3);
    expect(s.tabs).toHaveLength(4); // still open as a background tab
    expect(s.shown).toEqual(['d', 'c', 'b']); // 'a' left the screen
  });

  it('refocusing an existing tab does not duplicate it', () => {
    let s = { tabs: [], shown: [] };
    s = openTab(s.tabs, s.shown, tab('a'), 3);
    s = openTab(s.tabs, s.shown, tab('b'), 3);
    s = openTab(s.tabs, s.shown, tab('a'), 3);
    expect(s.tabs).toHaveLength(2);
    expect(s.shown).toEqual(['a', 'b']);
  });

  it('focusTab moves a background tab on screen', () => {
    expect(focusTab(['d', 'c', 'b'], 'a', 3)).toEqual(['a', 'd', 'c']);
  });

  it('closeTab removes from both lists', () => {
    const s = closeTab([tab('a'), tab('b')], ['b', 'a'], 'b');
    expect(s.tabs.map((t) => t.key)).toEqual(['a']);
    expect(s.shown).toEqual(['a']);
  });

  it('threadTabTitle prefers specific titles, falls back to preview', () => {
    expect(threadTabTitle({ title: 'Fix relay', title_is_generic: false })).toBe('Fix relay');
    expect(threadTabTitle({ title: 'Task', title_is_generic: true, preview: 'do things' })).toBe('do things');
    expect(threadTabTitle({})).toBe('(no preview)');
  });
});
