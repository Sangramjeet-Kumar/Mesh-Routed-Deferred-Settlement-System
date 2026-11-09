import { ArrowRight, Globe2, Radio, Smartphone, WifiOff } from 'lucide-react';
import type { MeshDevice } from '../../api/types';
import { deviceName } from '../../lib/format';
import { Button } from '../ui/Button';

interface Props {
  devices: MeshDevice[];
  rounds: number;
  busy: boolean;
  pendingAction?: string;
  onGossip: () => void;
  onFlush: () => void;
}

const positions: Record<string, [number, number]> = {
  'phone-alice': [94, 140], 'phone-stranger1': [280, 64], 'phone-stranger2': [280, 216],
  'phone-stranger3': [448, 140], 'phone-bridge': [630, 140],
};

export function MeshNetwork({ devices, rounds, busy, pendingAction, onGossip, onFlush }: Props) {
  const ordered = [...devices].sort((a, b) => a.deviceId.localeCompare(b.deviceId));
  const active = devices.some(device => device.packetCount > 0);
  const bridges = devices.filter(device => device.hasInternet);
  const carrying = devices.filter(device => device.packetCount > 0).length;

  return <section className="panel network" aria-labelledby="network-title">
    <div className="panel-heading"><div><span className="eyebrow">02 / PROPAGATE</span><h2 id="network-title">The mesh, in motion</h2></div><span className="live-label"><span className="status-dot" />Live topology</span></div>
    <div className="network-subheading"><p className="section-description">Untrusted devices. Encrypted cargo. One connected bridge.</p><span className="round-count">{rounds.toString().padStart(2, '0')} rounds this session</span></div>
    <div className={`mesh-canvas ${active ? 'mesh-active' : ''}`}>
      <svg viewBox="0 0 730 285" role="img" aria-labelledby="mesh-graphic-title mesh-graphic-description">
        <title id="mesh-graphic-title">Mesh device topology</title>
        <desc id="mesh-graphic-description">All simulated devices are within range. Lines show possible connections, not tracked packet routes. {carrying} devices currently hold packets.</desc>
        <defs><pattern id="mesh-dots" width="18" height="18" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="0.7" fill="currentColor" /></pattern></defs>
        <rect width="730" height="285" fill="url(#mesh-dots)" className="dot-grid" />
        <g className="network-lines">{ordered.flatMap((source, i) => ordered.slice(i + 1).map(target => {
          const from = positions[source.deviceId]; const to = positions[target.deviceId];
          if (!from || !to) return null;
          const shared = source.packetIds.some(id => target.packetIds.includes(id));
          return <line key={`${source.deviceId}-${target.deviceId}`} x1={from[0]} y1={from[1]} x2={to[0]} y2={to[1]} className={shared ? 'line-carrying' : ''} />;
        }))}</g>
        {ordered.map(device => {
          const position = positions[device.deviceId];
          if (!position) return null;
          const [x, y] = position;
          return <g key={device.deviceId} transform={`translate(${x}, ${y})`} className={`mesh-node ${device.hasInternet ? 'node-bridge' : ''} ${device.packetCount ? 'node-carrying' : ''}`}>
            <title>{deviceName(device.deviceId)}: {device.packetCount} packets; {device.hasInternet ? 'internet connected' : 'offline'}</title>
            {device.hasInternet && <circle r="41" className="bridge-halo" />}
            <rect x="-24" y="-24" width="48" height="48" rx="15" className="node-body" />
            {device.hasInternet ? <Globe2 x={-10} y={-10} width={20} height={20} /> : <Smartphone x={-10} y={-10} width={20} height={20} />}
            {device.packetCount > 0 && <g transform="translate(22,-20)"><circle r="10" className="node-counter" /><text textAnchor="middle" y="3.5" className="counter-text">{device.packetCount}</text></g>}
            <text textAnchor="middle" y="48" className="node-label">{deviceName(device.deviceId)}</text>
            <text textAnchor="middle" y="64" className="node-state">{device.hasInternet ? 'INTERNET CONNECTED' : 'OFFLINE'}</text>
          </g>;
        })}
      </svg>
      {!devices.length && <div className="network-placeholder">Waiting for mesh state…</div>}
    </div>
    <div className="mesh-legend"><span><span className="legend-line" />In range</span><span><span className="legend-line legend-active" />Shared packet</span><span className="mesh-summary">{carrying} / {devices.length} devices carrying packets</span></div>
    <div className="network-actions"><div><span className="step-label"><Radio size={14} />Gossip</span><p>Share packets with nearby devices.</p><Button onClick={onGossip} disabled={busy} loading={pendingAction === 'gossip'}>Run gossip round<ArrowRight size={15} /></Button></div><div><span className="step-label"><Globe2 size={14} />Bridge upload</span><p>{bridges.length} connected bridge{bridges.length === 1 ? '' : 's'} ready to deliver.</p><Button onClick={onFlush} disabled={busy} loading={pendingAction === 'flush'}>Upload to backend<ArrowRight size={15} /></Button></div></div>
    <details className="device-details"><summary>Inspect device packets <span>{devices.length} devices</span></summary><ul>{ordered.map(device => <li key={device.deviceId}><span>{device.hasInternet ? <Globe2 size={13} /> : <WifiOff size={13} />}{deviceName(device.deviceId)}</span><code>{device.packetIds.join(', ') || 'No packets'}</code></li>)}</ul></details>
  </section>;
}
