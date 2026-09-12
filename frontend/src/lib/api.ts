import type {
  ApiProblem,
  AuctionView,
  AuthMe,
  BalanceView,
  BidResult,
  CloseNowResult,
  ConfirmResult,
  FinishResult,
  JoinResult,
  KycStartResult,
  KycStatusResult,
  ProposalView,
  ReconnectState,
  SessionView,
} from './types';

const API_BASE_URL = (import.meta.env.VITE_TJ_API_BASE_URL as string | undefined)?.replace(/\/$/, '') ?? '';
const DEV_MODE = import.meta.env.VITE_TJ_DEV_MODE === 'true';

export class TiempoJustoApiError extends Error {
  readonly status: number;
  readonly problem: ApiProblem | null;

  constructor(status: number, problem: ApiProblem | null, fallback: string) {
    super(problem?.message ?? problem?.detail ?? fallback);
    this.name = 'TiempoJustoApiError';
    this.status = status;
    this.problem = problem;
  }
}

function accessToken(): string | null {
  return window.localStorage.getItem('tj.accessToken');
}

function devActorId(): string | null {
  if (!DEV_MODE) return null;
  return window.localStorage.getItem('tj.devActorId');
}

function buildHeaders(extra?: HeadersInit): Headers {
  const headers = new Headers(extra);
  headers.set('Accept', 'application/json');

  const token = accessToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const actorId = devActorId();
  if (actorId) headers.set('X-TJ-Actor-Id', actorId);

  return headers;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: buildHeaders(init.headers),
  });

  if (!response.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await response.json()) as ApiProblem;
    } catch {
      problem = null;
    }
    throw new TiempoJustoApiError(response.status, problem, `HTTP ${response.status}`);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

function json(body: unknown): RequestInit {
  return {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

export const tiempoJustoApi = {
  baseUrl: API_BASE_URL || window.location.origin,
  devMode: DEV_MODE,

  me(): Promise<AuthMe> {
    return request('/api/v1/auth/me');
  },

  startKyc(): Promise<KycStartResult> {
    return request('/api/v1/identity/verifications', { method: 'POST' });
  },

  latestKyc(): Promise<KycStatusResult> {
    return request('/api/v1/identity/verifications/latest');
  },

  createProposal(profileId: string, amountClp: number, durationMinutes: number): Promise<ProposalView> {
    return request(`/api/v1/profiles/${encodeURIComponent(profileId)}/proposals`, {
      method: 'POST',
      ...json({ modality: 'ONLINE', durationMinutes, amountClp }),
    });
  },

  getProposal(proposalId: string): Promise<ProposalView> {
    return request(`/api/v1/proposals/${encodeURIComponent(proposalId)}`);
  },

  getAuction(auctionId: string): Promise<AuctionView> {
    return request(`/api/v1/auctions/${encodeURIComponent(auctionId)}`);
  },

  placeBid(auctionId: string, amountClp: number): Promise<BidResult> {
    return request(`/api/v1/auctions/${encodeURIComponent(auctionId)}/bids`, {
      method: 'POST',
      ...json({ amountClp }),
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
    });
  },

  closeNow(auctionId: string): Promise<CloseNowResult> {
    return request(`/api/v1/auctions/${encodeURIComponent(auctionId)}/close-now`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    });
  },

  confirmWinner(appointmentId: string): Promise<ConfirmResult> {
    return request(`/api/v1/appointments/${encodeURIComponent(appointmentId)}/confirm-now`, {
      method: 'POST',
    });
  },

  getSession(sessionId: string): Promise<SessionView> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`);
  },

  joinSession(sessionId: string): Promise<JoinResult> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/join`, {
      method: 'POST',
      ...json({ cameraValid: true, mediaFlowing: true, audioMuted: false }),
    });
  },

  acceptPaid(sessionId: string): Promise<SessionView> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/paid-consent`, {
      method: 'POST',
    });
  },

  mediaSignal(sessionId: string, mediaFlowing: boolean): Promise<ReconnectState> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/media-signal`, {
      method: 'POST',
      ...json({ cameraValid: true, mediaFlowing, audioMuted: false }),
    });
  },

  getReconnectState(sessionId: string): Promise<ReconnectState> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/reconnect-state`);
  },

  acceptResume(sessionId: string): Promise<ReconnectState> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/resume-consent`, {
      method: 'POST',
    });
  },

  finishSession(sessionId: string): Promise<FinishResult> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/finish`, {
      method: 'POST',
    });
  },

  getBalance(): Promise<BalanceView> {
    return request('/api/v1/payments/balance');
  },
};

export function setDevActorId(actorId: string): void {
  if (!DEV_MODE) return;
  const clean = actorId.trim();
  if (clean) window.localStorage.setItem('tj.devActorId', clean);
  else window.localStorage.removeItem('tj.devActorId');
}

export function getDevActorId(): string {
  return DEV_MODE ? window.localStorage.getItem('tj.devActorId') ?? '' : '';
}
