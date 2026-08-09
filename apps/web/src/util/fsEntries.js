// One ordering for every file list: folders before files, real names
// before dotfiles within each group, then case-insensitive alphabetical.
// Used by the workspace files drawer and the folder picker so the two
// surfaces never disagree.
export function compareFsEntries(a, b) {
  if (!!b.isDirectory !== !!a.isDirectory) return b.isDirectory ? 1 : -1;
  const aDot = a.fileName.startsWith('.');
  const bDot = b.fileName.startsWith('.');
  if (aDot !== bDot) return aDot ? 1 : -1;
  return a.fileName.localeCompare(b.fileName, undefined, { sensitivity: 'base' });
}
