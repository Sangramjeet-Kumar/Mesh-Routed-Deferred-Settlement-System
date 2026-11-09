export interface Account {
  vpa: string;
  holderName: string;
  balance: number;
  version: number;
}

export interface LedgerTransaction {
  id: number;
  packetHash: string;
  senderVpa: string;
  receiverVpa: string;
  amount: number;
  signedAt: string;
  settledAt: string;
  bridgeNodeId: string;
  hopCount: number;
  status: 'SETTLED' | 'REJECTED';
}

export interface MeshDevice {
  deviceId: string;
  hasInternet: boolean;
  packetCount: number;
  packetIds: string[];
}

export interface MeshState {
  devices: MeshDevice[];
  idempotencyCacheSize: number;
}

export interface SendRequest {
  senderVpa: string;
  receiverVpa: string;
  amount: number;
  pin: string;
  ttl: number;
  startDevice: string;
}

export interface SendResult {
  packetId: string;
  ciphertextPreview: string;
  ttl: number;
  injectedAt: string;
}

export interface GossipResult {
  transfers: number;
  deviceCounts: Record<string, number>;
}

export type IngestOutcome = 'SETTLED' | 'DUPLICATE_DROPPED' | 'INVALID';

export interface UploadResult {
  bridgeNode: string;
  packetId: string;
  outcome: IngestOutcome;
  reason: string;
  transactionId: number;
}

export interface FlushResult {
  uploadsAttempted: number;
  results: UploadResult[];
}

export interface DashboardData {
  mesh: MeshState;
  accounts: Account[];
  transactions: LedgerTransaction[];
}

export type DemoAction = 'send' | 'gossip' | 'flush' | 'reset';

export interface ActivityEntry {
  id: number;
  at: Date;
  tone: 'info' | 'success' | 'warning' | 'error';
  title: string;
  detail: string;
}
