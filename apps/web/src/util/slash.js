// Parse `/<command> [<args>]` from the composer input. Matches Android's
// ViewModel.sendTurn parser so /cd, /pwd, /compact, /plan, /default,
// /collab all route the same way.
export function parseSlash(input) {
  const text = input.trim();
  if (!text.startsWith('/')) return null;
  const bare = text.slice(1).trim();
  if (!bare) return null;
  const sp = bare.indexOf(' ');
  const cmd = (sp === -1 ? bare : bare.slice(0, sp)).toLowerCase();
  const args = sp === -1 ? '' : bare.slice(sp + 1).trim();
  return { cmd, args };
}
