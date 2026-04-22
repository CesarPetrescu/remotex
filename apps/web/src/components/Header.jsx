import { StatusBadge } from './StatusBadge';
import { SCREENS } from '../config';

// Top bar across every screen. Shows the Remotex brand, the current
// session status, and a back affordance on non-root screens.
export function Header({ state, onBack }) {
  const showBack = state.screen !== SCREENS.Hosts;
  return (
    <header className="bar">
      {showBack ? (
        <button type="button" className="back" onClick={onBack} aria-label="Back">
          ←
        </button>
      ) : (
        <span className="back-spacer" />
      )}
      <span className="brand">REMOTEX</span>
      <StatusBadge status={state.status} />
    </header>
  );
}
