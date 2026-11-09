import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';

afterEach(() => vi.unstubAllGlobals());

describe('API contract', () => {
  it('keeps amount precision, the PIN, TTL, and start device in the send request', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ packetId: 'packet' })));
    vi.stubGlobal('fetch', fetch);
    const request = { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 0.005, pin: '1234', ttl: 5, startDevice: 'phone-alice' };
    await api.send(request);
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/demo/send', {
      method: 'POST', body: JSON.stringify(request), headers: { 'Content-Type': 'application/json' },
    });
  });

  it('reads dashboard resources in parallel and passes the cancellation signal', async () => {
    const signal = new AbortController().signal;
    const fetch = vi.fn().mockImplementation((url: string) => Promise.resolve(new Response(JSON.stringify(
      url.endsWith('state') ? { devices: [], idempotencyCacheSize: 0 } : [],
    ))));
    vi.stubGlobal('fetch', fetch);
    expect(await api.dashboard(signal)).toEqual({ mesh: { devices: [], idempotencyCacheSize: 0 }, accounts: [], transactions: [] });
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/mesh/state', '/api/accounts', '/api/transactions']);
    expect(fetch.mock.calls.every(call => call[1].signal === signal)).toBe(true);
  });

  it('keeps a legacy SETTLED upload outcome without interpreting it', async () => {
    const body = { uploadsAttempted: 1, results: [{ outcome: 'SETTLED', transactionId: 1 }] };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))));
    expect(await api.flush()).toEqual(body);
  });

  it('reports JSON HTTP errors without leaking HTML or retrying the request', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'Bad Request' }), { status: 400 }));
    vi.stubGlobal('fetch', fetch);
    await expect(api.gossip()).rejects.toThrow('Bad Request (HTTP 400).');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('reports a non-JSON proxy error clearly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy error</html>', { status: 502 })));
    await expect(api.flush()).rejects.toThrow('HTTP 502');
  });

  it('does not retry an uncertain POST after a network failure', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('network failed'));
    vi.stubGlobal('fetch', fetch);
    await expect(api.reset()).rejects.toThrow('Cannot reach the server');
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
