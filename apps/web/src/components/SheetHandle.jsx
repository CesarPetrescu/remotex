// Drag handle for phone bottom sheets. Rendered at the top of each
// drawer; a downward swipe of >70px dismisses. Desktop hides it via CSS.
export function SheetHandle({ onDismiss, label = 'Close sheet' }) {
  let startY = null;
  return (
    <button
      type="button"
      className="sheet-handle"
      aria-label={label}
      onClick={onDismiss}
      onTouchStart={(e) => {
        startY = e.touches[0]?.clientY ?? null;
      }}
      onTouchEnd={(e) => {
        const endY = e.changedTouches[0]?.clientY ?? null;
        if (startY != null && endY != null && endY - startY > 70) onDismiss?.();
        startY = null;
      }}
    />
  );
}
