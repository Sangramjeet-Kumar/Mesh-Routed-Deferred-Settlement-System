import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ActivityEntry, DemoAction, SendRequest, SendResult } from '../api/types';

type Action = { kind: 'send'; request: SendRequest } | { kind: Exclude<DemoAction, 'send'> };
const dashboardKey = ['dashboard'];

export function useDashboard() {
  const client = useQueryClient();
  const locked = useRef(false);
  const sequence = useRef(0);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [lastPacket, setLastPacket] = useState<SendResult | null>(null);
  const [rounds, setRounds] = useState(0);

  function record(entry: Omit<ActivityEntry, 'id' | 'at'>) {
    const next = { ...entry, id: ++sequence.current, at: new Date() };
    setActivity(previous => [next, ...previous].slice(0, 80));
  }

  const mutation = useMutation({
    retry: false,
    mutationFn: async (action: Action) => {
      await client.cancelQueries({ queryKey: dashboardKey });
      switch (action.kind) {
        case 'send': return { kind: 'send', data: await api.send(action.request) } as const;
        case 'gossip': return { kind: 'gossip', data: await api.gossip() } as const;
        case 'flush': return { kind: 'flush', data: await api.flush() } as const;
        case 'reset': return { kind: 'reset', data: await api.reset() } as const;
      }
    },
    onSuccess: async result => {
      switch (result.kind) {
        case 'send':
          setLastPacket(result.data);
          record({ tone: 'success', title: 'Encrypted packet injected',
            detail: `${result.data.packetId.slice(0, 8)} · ${result.data.injectedAt} · TTL ${result.data.ttl}` });
          break;
        case 'gossip':
          setRounds(value => value + 1);
          record({ tone: 'info', title: 'Gossip round complete',
            detail: `${result.data.transfers} packet transfer${result.data.transfers === 1 ? '' : 's'} across the mesh.` });
          break;
        case 'flush':
          if (result.data.results.length === 0) record({ tone: 'info', title: 'No packets to upload',
            detail: 'The bridge holds no packets. Inject a payment and run a gossip round.' });
          for (const upload of result.data.results) {
            record({ tone: upload.outcome === 'INVALID' ? 'error' : upload.outcome === 'DUPLICATE_DROPPED' ? 'warning' : 'success',
              title: `Upload response: ${upload.outcome}`,
              detail: `${upload.packetId} via ${upload.bridgeNode}${upload.transactionId >= 0 ? ` · transaction #${upload.transactionId}` : ''}${upload.reason ? ` · ${upload.reason}` : ''}` });
          }
          break;
        case 'reset':
          setLastPacket(null);
          setRounds(0);
          record({ tone: 'info', title: 'Mesh and cache cleared', detail: 'Account balances and ledger entries are retained.' });
          break;
      }
      await client.invalidateQueries({ queryKey: dashboardKey });
    },
    onError: async error => {
      record({ tone: 'error', title: 'Action could not be confirmed', detail: error.message });
      // Refresh GETs after an uncertain response; never repeat a POST automatically.
      await client.invalidateQueries({ queryKey: dashboardKey });
    },
    onSettled: () => { locked.current = false; },
  });

  const query = useQuery({
    queryKey: dashboardKey,
    queryFn: ({ signal }) => api.dashboard(signal),
    refetchInterval: mutation.isPending ? false : 3000,
    refetchOnWindowFocus: false,
    retry: false,
  });

  function run(action: Action) {
    if (locked.current) return;
    locked.current = true;
    mutation.mutate(action);
  }

  return {
    ...query, activity, lastPacket, rounds,
    run, busy: mutation.isPending,
    pendingAction: mutation.isPending ? mutation.variables?.kind : undefined,
    actionError: mutation.error,
    dismissError: mutation.reset,
  };
}
