import { Component, type ErrorInfo, type ReactNode } from 'react';
import { DashboardPage } from './pages/DashboardPage';

class DashboardBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('Dashboard rendering failed', error, info.componentStack); }
  render() {
    if (this.state.failed) return <main className="fatal-error"><h1>The dashboard could not be displayed.</h1><p>Reload to fetch the current state. No payment action will be retried automatically.</p><button className="button button-primary" onClick={() => window.location.reload()}>Reload dashboard</button></main>;
    return this.props.children;
  }
}

export default function App() {
  return <DashboardBoundary><DashboardPage /></DashboardBoundary>;
}
