import { ArrowUpRight, Wallet } from 'lucide-react';
import type { Account } from '../../api/types';
import { money } from '../../lib/format';

export function AccountBalances({ accounts }: { accounts: Account[] }) {
  return <section className="panel balances" aria-labelledby="balances-title">
    <div className="panel-heading"><div><span className="eyebrow">THE ACCOUNTS</span><h2 id="balances-title">Available balances</h2></div><Wallet size={18} className="muted-icon" /></div>
    <div className="account-grid">{accounts.map((account, index) => <div className="account-card" key={account.vpa}>
      <div className="account-top"><span className={`avatar avatar-${index % 4}`}>{account.holderName.charAt(0)}</span><div><strong>{account.holderName}</strong><span>{account.vpa}</span></div><ArrowUpRight size={15} /></div>
      <p className="account-amount">{money(account.balance)}</p><span className="account-caption">Current ledger balance</span>
    </div>)}</div>
    {!accounts.length && <p className="empty-inline">Account balances will appear when the server connects.</p>}
  </section>;
}
