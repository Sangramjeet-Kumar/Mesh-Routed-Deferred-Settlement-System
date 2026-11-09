import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Account, LedgerTransaction, MeshState } from '../api/types';
import { DashboardPage } from './DashboardPage';

let client: QueryClient;
let accounts: Account[];
let transactions: LedgerTransaction[];
let mesh: MeshState;
const fetchMock = vi.fn();
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  accounts = [
    { vpa: 'alice@demo', holderName: 'Alice', balance: 5000, version: 0 },
    { vpa: 'bob@demo', holderName: 'Bob', balance: 1000, version: 0 },
  ];
  transactions = [];
  mesh = { idempotencyCacheSize: 0, devices: [
    { deviceId: 'phone-alice', hasInternet: false, packetCount: 0, packetIds: [] },
    { deviceId: 'phone-bridge', hasInternet: true, packetCount: 0, packetIds: [] },
  ] };
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string, options?: RequestInit) => {
    if (url === '/api/accounts') return json(accounts);
    if (url === '/api/transactions') return json(transactions);
    if (url === '/api/mesh/state') return json(mesh);
    if (url === '/api/demo/send') {
      mesh.devices[0].packetCount = 1;
      mesh.devices[0].packetIds = ['01234567'];
      return json({ packetId: '01234567-89ab-cdef', ciphertextPreview: 'opaque...', ttl: 5, injectedAt: 'phone-alice' });
    }
    if (url === '/api/mesh/gossip') {
      mesh.devices[1].packetCount = 1;
      mesh.devices[1].packetIds = ['01234567'];
      return json({ transfers: 1, deviceCounts: { 'phone-alice': 1, 'phone-bridge': 1 } });
    }
    if (url === '/api/mesh/flush') {
      mesh.idempotencyCacheSize = 1;
      transactions = [{ id: 1, packetHash: 'a'.repeat(64), senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 500,
        signedAt: '2026-11-09T10:00:00Z', settledAt: '2026-11-09T10:01:00Z', bridgeNodeId: 'phone-bridge', hopCount: 1, status: 'REJECTED' }];
      return json({ uploadsAttempted: 1, results: [{ packetId: '01234567', bridgeNode: 'phone-bridge', outcome: 'SETTLED', reason: '', transactionId: 1 }] });
    }
    if (url === '/api/mesh/reset') {
      mesh.idempotencyCacheSize = 0;
      mesh.devices.forEach(device => { device.packetCount = 0; device.packetIds = []; });
      return json({ status: 'mesh and idempotency cache cleared' });
    }
    throw new Error(`Unexpected request ${options?.method ?? 'GET'} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => { client.clear(); vi.unstubAllGlobals(); });

async function mount() {
  render(<QueryClientProvider client={client}><DashboardPage /></QueryClientProvider>);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Encrypt & inject' })).toBeEnabled());
}

const posts = (path: string) => fetchMock.mock.calls.filter(call => call[0] === `/api${path}` && call[1]?.method === 'POST');

describe('Dashboard demo flow', () => {
  it('injects, gossips, uploads, and keeps response and ledger statuses independent', async () => {
    const user = userEvent.setup();
    await mount();
    await user.click(screen.getByRole('button', { name: 'Encrypt & inject' }));
    await screen.findByText('Packet injected into the mesh');
    expect(JSON.parse(posts('/demo/send')[0][1].body)).toEqual({
      senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 500, pin: '1234', ttl: 5, startDevice: 'phone-alice',
    });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run gossip round' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Run gossip round' }));
    await screen.findByText('Gossip round complete');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Upload to backend' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Upload to backend' }));
    await screen.findByText('Upload response: SETTLED');
    expect(await screen.findByText('REJECTED')).toBeVisible();
    expect(posts('/demo/send')).toHaveLength(1);
    expect(posts('/mesh/gossip')).toHaveLength(1);
    expect(posts('/mesh/flush')).toHaveLength(1);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reset mesh & cache' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Reset mesh & cache' }));
    await screen.findByText('Mesh and cache cleared');
    expect(screen.getByText('REJECTED')).toBeVisible();
    expect(screen.getByText('₹5,000.00')).toBeVisible();
  });

  it.each(['0.005', '-1', '0'])('does not introduce amount restrictions for %s', async amount => {
    const user = userEvent.setup();
    await mount();
    const input = screen.getByRole('spinbutton', { name: 'Amount in rupees' });
    await user.clear(input);
    await user.type(input, amount);
    await user.click(screen.getByRole('button', { name: 'Encrypt & inject' }));
    await waitFor(() => expect(posts('/demo/send')).toHaveLength(1));
    expect(JSON.parse(posts('/demo/send')[0][1].body).amount).toBe(Number(amount));
  });

  it('locks repeated clicks while a POST is pending', async () => {
    await mount();
    const handler = fetchMock.getMockImplementation()!;
    let complete!: (response: Response) => void;
    fetchMock.mockImplementation((url: string, options?: RequestInit) => url === '/api/demo/send'
      ? new Promise<Response>(resolve => { complete = resolve; }) : handler(url, options));
    const button = screen.getByRole('button', { name: 'Encrypt & inject' });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(posts('/demo/send')).toHaveLength(1));
    expect(screen.getByRole('button', { name: 'Run gossip round' })).toBeDisabled();
    await act(async () => complete(json({ packetId: 'packet-1', ciphertextPreview: 'opaque', ttl: 5, injectedAt: 'phone-alice' })));
    await screen.findByText('Packet injected into the mesh');
  });

  it('shows an uncertain action error and never retries the POST', async () => {
    const user = userEvent.setup();
    await mount();
    const handler = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((url: string, options?: RequestInit) => url === '/api/mesh/flush'
      ? Promise.reject(new TypeError('network failed')) : handler(url, options));
    await user.click(screen.getByRole('button', { name: 'Upload to backend' }));
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Action could not be confirmed')).toBeVisible();
    expect(within(alert).getByText(/No automatic retry was made/)).toBeVisible();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Upload to backend' })).toBeEnabled());
    expect(posts('/mesh/flush')).toHaveLength(1);
  });

  it('displays a connection failure and recovers through explicit refresh', async () => {
    const handler = fetchMock.getMockImplementation()!;
    fetchMock.mockRejectedValue(new TypeError('offline'));
    render(<QueryClientProvider client={client}><DashboardPage /></QueryClientProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load the dashboard');
    expect(screen.getByRole('button', { name: 'Encrypt & inject' })).toBeDisabled();
    fetchMock.mockImplementation(handler);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Encrypt & inject' })).toBeEnabled();
  });

  it('renders bridge metadata as text and filters ledger records locally', async () => {
    transactions = [{ id: 1, packetHash: 'a'.repeat(64), senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 5,
      signedAt: '2026-11-09T10:00:00Z', settledAt: '2026-11-09T10:01:00Z', bridgeNodeId: '<img src=x onerror=alert(1)>', hopCount: 1, status: 'REJECTED' }];
    await mount();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeVisible();
    expect(document.querySelector('img')).toBeNull();
    await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Filter ledger by status' }), 'SETTLED');
    expect(screen.getByText('No matching records')).toBeVisible();
    expect(screen.queryByText('REJECTED')).not.toBeInTheDocument();
  });
});
