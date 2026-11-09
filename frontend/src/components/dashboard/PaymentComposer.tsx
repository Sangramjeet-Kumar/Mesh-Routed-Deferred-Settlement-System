import { useState, type FormEvent } from 'react';
import { ArrowDownUp, ArrowUpRight, Check, LockKeyhole, Send, ShieldCheck } from 'lucide-react';
import type { Account, SendRequest, SendResult } from '../../api/types';
import { Button } from '../ui/Button';

interface Props {
  accounts: Account[];
  busy: boolean;
  sending: boolean;
  lastPacket: SendResult | null;
  onSend: (request: SendRequest) => void;
}

export function PaymentComposer({ accounts, busy, sending, lastPacket, onSend }: Props) {
  const [sender, setSender] = useState('alice@demo');
  const [receiver, setReceiver] = useState('bob@demo');
  const [amount, setAmount] = useState('500');
  const [pin, setPin] = useState('1234');
  const options = accounts.length ? accounts : [
    { vpa: 'alice@demo', holderName: 'Alice' }, { vpa: 'bob@demo', holderName: 'Bob' },
    { vpa: 'carol@demo', holderName: 'Carol' }, { vpa: 'dave@demo', holderName: 'Dave' },
  ];

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Preserve the demo's numeric conversion and accepted amounts, including extra decimals.
    onSend({ senderVpa: sender, receiverVpa: receiver, amount: Number.parseFloat(amount), pin, ttl: 5, startDevice: 'phone-alice' });
  }

  return <section className="panel composer" aria-labelledby="compose-title">
    <div className="panel-heading"><div><span className="eyebrow">01 / CREATE</span><h2 id="compose-title">Compose a payment</h2></div><span className="icon-tile"><Send size={19} /></span></div>
    <p className="section-description">A payment starts offline. Its journey starts here.</p>
    <form onSubmit={submit} noValidate>
      <div className="account-fields">
        <label>From<select value={sender} onChange={event => setSender(event.target.value)}>{options.map(account => <option key={account.vpa} value={account.vpa}>{account.holderName} · {account.vpa}</option>)}</select></label>
        <button className="swap-button" type="button" aria-label="Swap sender and receiver" onClick={() => { setSender(receiver); setReceiver(sender); }}><ArrowDownUp size={16} /></button>
        <label>To<select value={receiver} onChange={event => setReceiver(event.target.value)}>{options.map(account => <option key={account.vpa} value={account.vpa}>{account.holderName} · {account.vpa}</option>)}</select></label>
      </div>
      <div className="amount-field"><label htmlFor="amount">Amount</label><div><span aria-hidden="true">₹</span><input id="amount" name="amount" type="number" step="any" inputMode="decimal" value={amount} onChange={event => setAmount(event.target.value)} aria-label="Amount in rupees" /></div><span className="currency-label">INR</span></div>
      <div className="pin-row"><label htmlFor="pin"><LockKeyhole size={14} />Demo PIN</label><input id="pin" type="password" value={pin} maxLength={4} onChange={event => setPin(event.target.value)} autoComplete="off" aria-label="Demo PIN" /><span>Simulated sender</span></div>
      <Button type="submit" variant="primary" className="send-button" disabled={busy} loading={sending}>Encrypt & inject<ArrowUpRight size={17} /></Button>
    </form>
    <div className="encryption-note"><ShieldCheck size={15} /><span>RSA-OAEP + AES-256-GCM</span><span className="tiny-dot" /><span>TTL 5</span></div>
    {lastPacket && <div className="packet-receipt" role="status"><Check size={16} /><div><strong>Packet injected into the mesh</strong><code>{lastPacket.packetId.slice(0, 8)} · {lastPacket.injectedAt}</code></div><details><summary>Details</summary><p>TTL {lastPacket.ttl}</p><code>{lastPacket.ciphertextPreview}</code></details></div>}
  </section>;
}
