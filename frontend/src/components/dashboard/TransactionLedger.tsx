import { useState } from 'react';
import { ArrowDownLeft, ArrowRight, ListFilter, ReceiptText } from 'lucide-react';
import type { LedgerTransaction } from '../../api/types';
import { money, time } from '../../lib/format';
import { StatusBadge } from '../ui/StatusBadge';

export function TransactionLedger({ transactions }: { transactions: LedgerTransaction[] }) {
  const [filter, setFilter] = useState('ALL');
  const visible = transactions.filter(tx => filter === 'ALL' || tx.status === filter);
  return <section className="panel ledger" id="ledger" aria-labelledby="ledger-title">
    <div className="panel-heading"><div><span className="eyebrow">03 / SETTLE</span><h2 id="ledger-title">Transaction ledger <span className="count-badge">{transactions.length}</span></h2></div><label className="filter-control"><ListFilter size={14} /><span className="sr-only">Filter ledger by status</span><select value={filter} onChange={event => setFilter(event.target.value)}><option value="ALL">All statuses</option><option value="SETTLED">Settled</option><option value="REJECTED">Rejected</option></select></label></div>
    <p className="section-description">The latest 20 records. Statuses are read directly from the ledger.</p>
    {visible.length ? <div className="table-scroll" tabIndex={0} role="region" aria-label="Transaction ledger table"><table><thead><tr><th>Transaction</th><th>From / to</th><th>Amount</th><th>Ledger status</th><th>Bridge / hops</th><th>Processed</th></tr></thead><tbody>{visible.map(tx => <tr key={tx.id}>
      <td><span className="transaction-id"><span className="transaction-icon"><ArrowDownLeft size={15} /></span>#{String(tx.id).padStart(4, '0')}</span><code className="hash-preview" title={tx.packetHash}>{tx.packetHash.slice(0, 10)}…</code></td>
      <td><span className="transaction-route">{tx.senderVpa}<ArrowRight size={12} /></span><span className="cell-secondary">{tx.receiverVpa}</span></td>
      <td className="money-cell">{money(tx.amount)}</td><td><StatusBadge status={tx.status} /></td>
      <td><span className="bridge-id">{tx.bridgeNodeId}</span><span className="cell-secondary">{tx.hopCount} hop{tx.hopCount === 1 ? '' : 's'}</span></td>
      <td><time dateTime={tx.settledAt} title={new Date(tx.settledAt).toLocaleString()}>{time(tx.settledAt)}</time><span className="cell-secondary">{new Date(tx.settledAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}</span></td>
    </tr>)}</tbody></table></div> : <div className="empty-state"><span className="empty-icon"><ReceiptText size={26} /></span><h3>{transactions.length ? 'No matching records' : 'Every journey leaves a record'}</h3><p>{transactions.length ? 'Choose another status to see more transactions.' : 'Inject a payment, run gossip, then upload from the bridge. Your ledger will appear here.'}</p></div>}
  </section>;
}
