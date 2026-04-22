export function FileRow({ entry, onOpenDir }) {
  const dir = entry.isDirectory;
  return (
    <button
      type="button"
      className={`fs-row ${dir ? 'dir' : 'file'}`}
      onClick={() => dir && onOpenDir()}
      disabled={!dir}
    >
      <span className="fs-icon">{dir ? '▸' : ' '}</span>
      <span className="fs-name">{entry.fileName}</span>
    </button>
  );
}
