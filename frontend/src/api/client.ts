import type { Account, DashboardData, FlushResult, GossipResult, LedgerTransaction, MeshState, SendRequest, SendResult } from './types';

export class ApiError extends Error {
  constructor(message: string, public readonly status?: number) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError('Cannot reach the server. Check that Spring Boot is running.');
  }

  const body = await response.text();
  let data: unknown;
  try {
    data = body ? JSON.parse(body) : undefined;
  } catch {
    throw new ApiError(response.ok
      ? 'The server returned an unexpected response. Please refresh.'
      : `The request failed (HTTP ${response.status}). Please check the server logs.`, response.status);
  }
  if (!response.ok) {
    const details = data as { message?: unknown; error?: unknown } | undefined;
    const message = typeof details?.message === 'string' && details.message
      ? details.message : typeof details?.error === 'string' ? details.error : 'Request failed';
    throw new ApiError(`${message} (HTTP ${response.status}).`, response.status);
  }
  return data as T;
}

export const api = {
  async dashboard(signal?: AbortSignal): Promise<DashboardData> {
    const [mesh, accounts, transactions] = await Promise.all([
      request<MeshState>('/mesh/state', { signal }),
      request<Account[]>('/accounts', { signal }),
      request<LedgerTransaction[]>('/transactions', { signal }),
    ]);
    return { mesh, accounts, transactions };
  },
  send: (body: SendRequest) => request<SendResult>('/demo/send', { method: 'POST', body: JSON.stringify(body) }),
  gossip: () => request<GossipResult>('/mesh/gossip', { method: 'POST' }),
  flush: () => request<FlushResult>('/mesh/flush', { method: 'POST' }),
  reset: () => request<{ status: string }>('/mesh/reset', { method: 'POST' }),
};
