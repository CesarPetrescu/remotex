import { StatusBadge } from './StatusBadge';
import { SCREENS } from '../config';

// Top bar across every screen. Brand and an explicit "Dashboard" pill
// both jump back to the wide-screen Hosts/Dashboard view from anywhere
// — the existing `←` button walks the stack one level (Session →
// Threads → Hosts), but the user wants an unambiguous one-tap home.
export function Header({ state, onBack, onSearch, onDashboard }) {
  const onHome = state.screen === SCREENS.Hosts;
  return (
    <header className="bar">
      {!onHome ? (
        <button
          type="button"
          className="back"
          onClick={onBack}
          aria-label="Back"
          title="Back"
        >
          ←
        </button>
      ) : (
        <span className="back-spacer" />
      )}
      <button
        type="button"
        className="brand brand-button"
        onClick={onDashboard}
        title="Dashboard"
      >
        REMOTEX
      </button>
      {!onHome && (
        <button
          type="button"
          className="header-home"
          onClick={onDashboard}
          title="Back to dashboard"
        >
          ⌂ Dashboard
        </button>
      )}
      <button type="button" className="header-search" onClick={onSearch}>
        Search
      </button>
      <StatusBadge status={state.status} />
    </header>
  );
}
