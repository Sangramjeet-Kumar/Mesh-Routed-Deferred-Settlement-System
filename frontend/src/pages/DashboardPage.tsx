import { Activity, ArrowDown, ArrowUpRight, Box, CircleHelp, Layers3, Network, RefreshCw, RotateCcw, ShieldCheck, Wifi, X } from 'lucide-react';
import { useDashboard } from '../hooks/useDashboard';
import { PaymentComposer } from '../components/dashboard/PaymentComposer';
import { MeshNetwork } from '../components/dashboard/MeshNetwork';
import { AccountBalances } from '../components/dashboard/AccountBalances';
import { TransactionLedger } from '../components/dashboard/TransactionLedger';
import { ActivityFeed } from '../components/dashboard/ActivityFeed';
import { Button } from '../components/ui/Button';
import { time } from '../lib/format';

export function DashboardPage() {
  const dashboard = useDashboard();
  const devices = dashboard.data?.mesh.devices ?? [];
  const accounts = dashboard.data?.accounts ?? [];
  const transactions = dashboard.data?.transactions ?? [];
  const uniquePackets = new Set(devices.flatMap(device => device.packetIds)).size;
  const copies = devices.reduce((sum, device) => sum + device.packetCount, 0);
  const connected = Boolean(dashboard.data) && !dashboard.isError;
  const disabled = dashboard.busy || !dashboard.data;

  return <div className="app-shell">
    <a className="skip-link" href="#main">Skip to dashboard</a>
    <aside className="sidebar" aria-label="Application navigation">
      <a href="#" className="brand" aria-label="Mesh Settlement Lab home"><span className="brand-mark"><Network size={23} strokeWidth={1.6} /></span><span>mesh<span className="brand-period">.</span><small>SETTLEMENT LAB</small></span></a>
      <div className="nav-section-label">WORKSPACE</div>
      <a className="nav-item nav-active" href="#main" aria-current="page"><Layers3 size={17} />Overview<span className="nav-indicator" /></a>
      <a className="nav-item" href="#network"><Network size={17} />Mesh network</a>
      <a className="nav-item" href="#ledger"><Activity size={17} />Ledger</a>
      <div className="sidebar-bottom"><div className="sandbox-note"><span className="sandbox-symbol"><Box size={19} /></span><strong>A little offline.<br />A lot connected.</strong><p>A sandbox for payments that find their own way.</p><span className="sandbox-badge">DEMO ENVIRONMENT</span></div><a className="help-link" href="#how-it-works"><CircleHelp size={16} />How it works<ArrowUpRight size={13} /></a><div className="sidebar-version"><span className="tiny-dot" />Mesh-routed settlement<span>v1.0</span></div></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><div className="breadcrumb">Workspace<span>/</span><strong>Overview</strong></div><div className="topbar-right"><span className="environment-label">SANDBOX</span><span className={`connection ${connected ? '' : 'connection-error'}`} role="status"><span className="status-dot" />{dashboard.isPending ? 'Connecting' : connected ? 'Backend connected' : 'Connection interrupted'}</span><span className="user-avatar" title="Demo workspace">M</span></div></header>
      <main id="main" tabIndex={-1}>
        <section className="page-intro"><div><div className="eyebrow intro-eyebrow"><span className="tiny-dot" />OFFLINE FIRST. CONNECTED EVENTUALLY.</div><h1>Good payments find a way<span>.</span></h1><p>From an offline phone to a settled ledger. Explore the journey.</p></div><div className="intro-actions"><Button variant="ghost" onClick={() => void dashboard.refetch()} disabled={dashboard.busy || dashboard.isFetching} aria-label="Refresh dashboard"><RefreshCw size={15} className={dashboard.isFetching ? 'spin' : ''} />Refresh</Button><Button variant="ghost" className="reset-button" onClick={() => dashboard.run({ kind: 'reset' })} disabled={disabled} loading={dashboard.pendingAction === 'reset'} title="Clear mesh packets and idempotency cache; keep balances and ledger"><RotateCcw size={15} />Reset mesh & cache</Button></div></section>
        {dashboard.isError && <div className="alert alert-error" role="alert"><div><strong>{dashboard.data ? 'Live updates paused' : 'Unable to load the dashboard'}</strong><p>{dashboard.error?.message} {dashboard.data ? 'Showing the last received data.' : ''}</p></div><Button onClick={() => void dashboard.refetch()} disabled={dashboard.isFetching}>Try again</Button></div>}
        {dashboard.actionError && <div className="alert alert-error" role="alert"><div><strong>Action could not be confirmed</strong><p>{dashboard.actionError.message} No automatic retry was made. Refresh the state before repeating the action.</p></div><Button variant="ghost" onClick={dashboard.dismissError} aria-label="Dismiss action error"><X size={17} /></Button></div>}
        <section className="stats-grid" aria-label="Mesh summary" aria-busy={dashboard.isPending}>
          <div className="stat-card"><span className="stat-icon"><Network size={18} /></span><div><span className="stat-label">Devices in mesh</span><strong>{dashboard.data ? devices.length.toString().padStart(2, '0') : '—'}<small>{devices.filter(device => !device.hasInternet).length} offline nodes</small></strong></div></div>
          <div className="stat-card"><span className="stat-icon mint"><Wifi size={18} /></span><div><span className="stat-label">Connected bridges</span><strong>{dashboard.data ? devices.filter(device => device.hasInternet).length.toString().padStart(2, '0') : '—'}<small className="mint-text">Internet enabled</small></strong></div></div>
          <div className="stat-card"><span className="stat-icon blue"><Box size={18} /></span><div><span className="stat-label">Packets in mesh</span><strong>{dashboard.data ? uniquePackets.toString().padStart(2, '0') : '—'}<small>{copies} stored copies</small></strong></div></div>
          <div className="stat-card"><span className="stat-icon amber"><ShieldCheck size={18} /></span><div><span className="stat-label">Claimed packet hashes</span><strong>{dashboard.data ? dashboard.data.mesh.idempotencyCacheSize.toString().padStart(2, '0') : '—'}<small>Idempotency cache</small></strong></div></div>
        </section>
        <div className="demo-banner" id="how-it-works"><span className="banner-icon"><Network size={18} /></span><div><strong>One packet. A network of possibilities.</strong><span>Create an encrypted payment, share it through the mesh, then let a bridge deliver it.</span></div><a href="#network">Follow the flow<ArrowDown size={14} /></a></div>
        <div className="flow-grid" id="network"><PaymentComposer accounts={accounts} busy={disabled} sending={dashboard.pendingAction === 'send'} lastPacket={dashboard.lastPacket} onSend={request => dashboard.run({ kind: 'send', request })} /><MeshNetwork devices={devices} rounds={dashboard.rounds} busy={disabled} pendingAction={dashboard.pendingAction} onGossip={() => dashboard.run({ kind: 'gossip' })} onFlush={() => dashboard.run({ kind: 'flush' })} /></div>
        <AccountBalances accounts={accounts} />
        <div className="results-grid"><TransactionLedger transactions={transactions} /><ActivityFeed entries={dashboard.activity} /></div>
        <footer className="page-footer"><span><ShieldCheck size={13} />Encrypted in transit through the mesh. Settled when connected.</span><span>{dashboard.dataUpdatedAt ? `Last synced ${time(dashboard.dataUpdatedAt)}` : 'Awaiting first sync'}<span className="footer-divider">/</span>Refreshes every 3s</span></footer>
      </main>
    </div>
  </div>;
}
