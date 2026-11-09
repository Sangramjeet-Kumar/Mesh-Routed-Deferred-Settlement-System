interface Props { status: string; }

export function StatusBadge({ status }: Props) {
  const tone = status === 'SETTLED' ? 'success' : status === 'DUPLICATE_DROPPED' ? 'warning' : 'error';
  return <span className={`badge badge-${tone}`}><span className="status-dot" />{status}</span>;
}
