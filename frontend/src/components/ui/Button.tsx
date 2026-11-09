import type { ButtonHTMLAttributes } from 'react';
import { LoaderCircle } from 'lucide-react';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  loading?: boolean;
}

export function Button({ variant = 'secondary', loading, children, className = '', disabled, ...props }: Props) {
  return <button type="button" className={`button button-${variant} ${className}`} disabled={disabled || loading} {...props}>
    {loading && <LoaderCircle size={16} className="spin" aria-hidden="true" />}{children}
  </button>;
}
