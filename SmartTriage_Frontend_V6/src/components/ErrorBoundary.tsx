/**
 * ErrorBoundary — clinical-safety failsafe for the React tree.
 *
 * Why this exists
 * ---------------
 * Without a boundary, a single thrown error during render unmounts the
 * entire subtree silently. In production this looked like the
 * dashboard rendering as a blank gradient with no sidebar and no
 * content — the exact "had to reload to make it work" failure the
 * user reported. A reload re-runs the module, re-fetches data, and
 * usually clears the transient race that triggered the throw, which
 * is why reload "fixes" it.
 *
 * This boundary catches anything that escapes a child component's
 * render and shows a recoverable fallback with:
 *   - the error message (so we can actually diagnose user reports
 *     instead of guessing from a screenshot)
 *   - a "Try again" button that resets the boundary state without a
 *     full page reload
 *   - a "Reload page" button as the last-resort hard reset
 *
 * It is intentionally a class component — that's the only API React
 * exposes for error boundaries.
 *
 * Placement: wrap each top-level Route element inside AppContent so
 * a thrown error in /dashboard doesn't kill /alerts, and vice versa.
 * The sidebar stays mounted regardless, so the user can navigate
 * away from a broken route.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle, RotateCcw, RefreshCw } from 'lucide-react';

interface Props {
  children: ReactNode;
  /** Optional label shown in the fallback ("Dashboard", "Patients", etc.). */
  routeLabel?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null, errorInfo: null };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    // Surface to console so the developer console shows the full
    // stack trace. Clinical incidents end up in browser logs the
    // user can share with us.
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary] caught render error:', error, errorInfo);
    this.setState({ errorInfo });
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null, errorInfo: null });
  };

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) return this.props.children;

    const label = this.props.routeLabel ?? 'page';
    const message = this.state.error?.message ?? 'Unknown error';

    return (
      <div className="min-h-screen flex items-center justify-center p-8">
        <div
          className="max-w-xl w-full rounded-2xl p-8"
          style={{
            background: 'rgba(255,255,255,0.92)',
            backdropFilter: 'blur(40px)',
            border: '1px solid rgba(255,255,255,0.7)',
            boxShadow: '0 20px 60px rgba(0,0,0,0.12)',
          }}
        >
          <div className="flex items-start gap-4">
            <div
              className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
              style={{ background: 'rgba(239,68,68,0.12)' }}
            >
              <AlertTriangle className="w-6 h-6 text-red-600" />
            </div>
            <div className="flex-1 min-w-0">
              <h2 className="text-lg font-semibold text-slate-900">
                Something went wrong loading the {label}.
              </h2>
              <p className="mt-1 text-sm text-slate-600">
                The page hit an unexpected error. Your session is still
                active — you can retry, navigate elsewhere from the
                sidebar, or reload.
              </p>
              <div
                className="mt-4 p-3 rounded-lg text-xs font-mono text-slate-700 break-words"
                style={{ background: 'rgba(241,245,249,0.8)' }}
              >
                {message}
              </div>
              <div className="mt-5 flex flex-wrap gap-3">
                <button
                  onClick={this.handleReset}
                  className="px-4 py-2 rounded-lg text-sm font-medium text-white inline-flex items-center gap-2"
                  style={{ background: '#0284c7' }}
                >
                  <RotateCcw className="w-4 h-4" />
                  Try again
                </button>
                <button
                  onClick={this.handleReload}
                  className="px-4 py-2 rounded-lg text-sm font-medium text-slate-700 inline-flex items-center gap-2"
                  style={{ background: 'rgba(255,255,255,0.6)', border: '1px solid rgba(203,213,225,0.6)' }}
                >
                  <RefreshCw className="w-4 h-4" />
                  Reload page
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }
}
