import type {
  AdminAuctionBundle,
  AdminRow,
  AdminSessionBundle,
  ApiProblem,
  AuctionPageView,
  AuctionView,
  AuthMe,
  BalanceView,
  BidResult,
  BlockState,
  CloseNowResult,
  ConfirmResult,
  DiscoveryPage,
  FinishResult,
  JoinResult,
  KycStartResult,
  KycStatusResult,
  ProfileView,
  ProposalView,
  ReconnectState,
  SafetyReportResult,
  SessionView,
  WebRtcConfig,
  WebRtcSignalBatch,
  WebRtcSignalInput,
} from './types';

const API_BASE_URL = (import.meta.env.VITE_TJ_API_BASE_URL as string | undefined)?.replace(/\/$/, '') ?? '';
const DEV_MODE = import.meta.env.VITE_TJ_DEV_MODE === 'true';
const ACCESS_TOKEN_KEY = 'tj.accessToken';

export const DEFAULT_API_TIMEOUT_MS = 15_000;

export type TiempoJustoNetworkErrorKind = 'offline' | 'timeout' | 'network';

export class TiempoJustoNetworkError extends Error {
  readonly kind: TiempoJustoNetworkErrorKind;

  constructor(kind: TiempoJustoNetworkErrorKind, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'TiempoJustoNetworkError';
    this.kind = kind;
  }
}

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
  return window.sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? window.localStorage.getItem(ACCESS_TOKEN_KEY);
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
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    throw new TiempoJustoNetworkError('offline', 'Sin conexión. Revisa tu red e inténtalo nuevamente.');
  }

  const controller = new AbortController();
  let timedOut = false;
  const externalSignal = init.signal;
  const abortFromExternalSignal = () => controller.abort(externalSignal?.reason);

  if (externalSignal?.aborted) abortFromExternalSignal();
  else externalSignal?.addEventListener('abort', abortFromExternalSignal, { once: true });

  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort(new DOMException('TiempoJusto API request timed out', 'TimeoutError'));
  }, DEFAULT_API_TIMEOUT_MS);

  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      signal: controller.signal,
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
  } catch (cause) {
    if (cause instanceof TiempoJustoApiError) throw cause;
    if (timedOut) {
      throw new TiempoJustoNetworkError('timeout', 'La solicitud tardó demasiado. Inténtalo nuevamente.', { cause });
    }
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      throw new TiempoJustoNetworkError('offline', 'Sin conexión. Revisa tu red e inténtalo nuevamente.', { cause });
    }
    if (externalSignal?.aborted) throw cause;
    throw new TiempoJustoNetworkError('network', 'No se pudo contactar al servicio. Inténtalo nuevamente.', { cause });
  } finally {
    window.clearTimeout(timeout);
    externalSignal?.removeEventListener('abort', abortFromExternalSignal);
  }
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

  discoverOnline(limit = 20): Promise<DiscoveryPage> {
    return request(`/api/v1/discovery?modality=ONLINE&limit=${Math.max(1, Math.min(limit, 50))}`);
  },

  getProfile(profileId: string): Promise<ProfileView> {
    return request(`/api/v1/profiles/${encodeURIComponent(profileId)}`);
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

  listAuctions(limit = 20): Promise<AuctionPageView> {
    return request(`/api/v1/auctions?limit=${Math.max(1, Math.min(limit, 50))}`);
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

  getWebRtcConfig(sessionId: string): Promise<WebRtcConfig> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/webrtc/config`);
  },

  sendWebRtcSignal(sessionId: string, signal: WebRtcSignalInput): Promise<{ sequence: number; expiresAt: string }> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/webrtc/signals`, {
      method: 'POST',
      ...json(signal),
    });
  },

  pollWebRtcSignals(sessionId: string, after: number): Promise<WebRtcSignalBatch> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/webrtc/signals?after=${Math.max(0, after)}`);
  },

  acceptPaid(sessionId: string): Promise<SessionView> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/paid-consent`, {
      method: 'POST',
    });
  },

  mediaSignal(sessionId: string, mediaFlowing: boolean): Promise<ReconnectState> {
    return request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/online/media-signal`, {
      method: 'POST',
      ...json({ cameraValid: mediaFlowing, mediaFlowing, audioMuted: false }),
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

  createSafetyReport(targetUserId: string, category: string, description: string, appointmentId?: string): Promise<SafetyReportResult> {
    return request('/api/v1/reports', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({ targetUserId, appointmentId: appointmentId?.trim() || null, category, description }),
    });
  },

  getBlockState(userId: string): Promise<BlockState> {
    return request(`/api/v1/users/${encodeURIComponent(userId)}/block`);
  },

  blockUser(userId: string): Promise<BlockState> {
    return request(`/api/v1/users/${encodeURIComponent(userId)}/block`, { method: 'POST' });
  },

  unblockUser(userId: string): Promise<BlockState> {
    return request(`/api/v1/users/${encodeURIComponent(userId)}/block`, { method: 'DELETE' });
  },

  adminUsers(query = ''): Promise<AdminRow[]> {
    return request(`/api/v1/admin/users?query=${encodeURIComponent(query)}`);
  },

  adminAuction(auctionId: string): Promise<AdminAuctionBundle> {
    return request(`/api/v1/admin/auctions/${encodeURIComponent(auctionId)}`);
  },

  adminSession(sessionId: string): Promise<AdminSessionBundle> {
    return request(`/api/v1/admin/sessions/${encodeURIComponent(sessionId)}`);
  },

  adminReviewQueue(): Promise<AdminRow[]> {
    return request('/api/v1/admin/human-review-queue');
  },

  adminClaimReview(taskId: string): Promise<AdminRow> {
    return request(`/api/v1/admin/human-review-queue/${encodeURIComponent(taskId)}/claim`, { method: 'POST' });
  },

  adminSafetyCase(caseId: string): Promise<AdminRow> {
    return request(`/api/v1/admin/safety-cases/${encodeURIComponent(caseId)}`);
  },

  adminEvidenceTimeline(caseId: string): Promise<AdminRow[]> {
    return request(`/api/v1/admin/safety-cases/${encodeURIComponent(caseId)}/evidence-timeline`);
  },

  adminDecideCase(caseId: string, outcome: string, finalSeverity: string, reason: string): Promise<AdminRow> {
    return request(`/api/v1/admin/safety-cases/${encodeURIComponent(caseId)}/decision`, {
      method: 'POST',
      ...json({ outcome, finalSeverity, reason }),
    });
  },

  adminAppeals(): Promise<AdminRow[]> {
    return request('/api/v1/admin/appeals');
  },

  adminResolveAppeal(appealId: string, outcome: string, reason: string): Promise<AdminRow> {
    return request(`/api/v1/admin/appeals/${encodeURIComponent(appealId)}/resolve`, {
      method: 'POST',
      ...json({ outcome, reason }),
    });
  },

  adminRiskSignals(userId = ''): Promise<AdminRow[]> {
    const suffix = userId.trim() ? `?userId=${encodeURIComponent(userId.trim())}` : '';
    return request(`/api/v1/admin/risk-signals${suffix}`);
  },

  adminPayout(payoutId: string): Promise<AdminRow> {
    return request(`/api/v1/admin/finance/payouts/${encodeURIComponent(payoutId)}`);
  },

  adminPayoutHolds(payoutId: string): Promise<AdminRow[]> {
    return request(`/api/v1/admin/finance/payouts/${encodeURIComponent(payoutId)}/holds`);
  },

  adminCreatePayoutHold(payoutId: string, reasonCode: string, evidenceRef: string, safetyCaseId?: string): Promise<AdminRow> {
    return request(`/api/v1/admin/finance/payouts/${encodeURIComponent(payoutId)}/holds`, {
      method: 'POST',
      ...json({ reasonCode, evidenceRef, safetyCaseId: safetyCaseId?.trim() || null }),
    });
  },

  adminReleasePayoutHold(payoutId: string, holdId: string, reason: string): Promise<AdminRow> {
    return request(`/api/v1/admin/finance/payouts/${encodeURIComponent(payoutId)}/holds/${encodeURIComponent(holdId)}/release`, {
      method: 'POST',
      ...json({ reason }),
    });
  },

  adminLedger(transactionId: string): Promise<AdminRow[]> {
    return request(`/api/v1/admin/finance/ledger/${encodeURIComponent(transactionId)}`);
  },

  adminAuditLog(caseId = ''): Promise<AdminRow[]> {
    const suffix = caseId.trim() ? `?caseId=${encodeURIComponent(caseId.trim())}` : '';
    return request(`/api/v1/admin/audit-log${suffix}`);
  },

  adminActions(targetId = ''): Promise<AdminRow[]> {
    const suffix = targetId.trim() ? `?targetId=${encodeURIComponent(targetId.trim())}` : '';
    return request(`/api/v1/admin/admin-actions${suffix}`);
  },
};

export function setAccessToken(token: string): void {
  const clean = token.trim();
  window.localStorage.removeItem(ACCESS_TOKEN_KEY);
  if (clean) window.sessionStorage.setItem(ACCESS_TOKEN_KEY, clean);
  else window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function getAccessToken(): string {
  return accessToken() ?? '';
}

export function clearAccessToken(): void {
  window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  window.localStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function setDevActorId(actorId: string): void {
  if (!DEV_MODE) return;
  const clean = actorId.trim();
  if (clean) window.localStorage.setItem('tj.devActorId', clean);
  else window.localStorage.removeItem('tj.devActorId');
}

export function getDevActorId(): string {
  return DEV_MODE ? window.localStorage.getItem('tj.devActorId') ?? '' : '';
}
