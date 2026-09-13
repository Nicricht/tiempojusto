export type AuthMe = {
  userId: string;
  publicId: string;
  role: string;
  accountStatus: string;
};

export type KycStartResult = {
  verificationId: string;
  provider: string;
  verificationUrl: string;
  status: string;
};

export type KycStatusResult = {
  verificationId: string;
  provider: string;
  status: string;
  verifiedAdult: boolean;
  legalCountryCode: string | null;
  verifiedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
};

export type ProfileView = {
  id: string;
  userId: string;
  username: string;
  displayName: string;
  bio: string;
  publicAge: number | null;
  modalities: string[];
  approximateZone: string | null;
  mapVisible: boolean;
  selfDeclaredAttributes: Record<string, string>;
  profileStatus: string;
  version: number;
};

export type DiscoveryPage = {
  items: ProfileView[];
  nextCursor: string | null;
  hasMore: boolean;
};

export type ProposalView = {
  id: string;
  bidderUserId: string;
  hostProfileId: string;
  modality: string;
  durationMinutes: number;
  amountClp: number;
  status: string;
  validUntil: string;
  withdrawnAt: string | null;
  cooldownUntil: string | null;
  lockVersion: number;
};

export type AuctionView = {
  id: string;
  hostUserId: string;
  modality: string;
  durationMinutes: number;
  openingAmountClp: number;
  currentAmountClp: number;
  nextActionableAmountClp: number;
  closeNowAmountClp: number | null;
  status: string;
  startedAt: string;
  effectiveEndAt: string;
  winnerUserId: string | null;
  winningBidId: string | null;
  lockVersion: number;
};

export type AuctionPageView = {
  items: AuctionView[];
  nextCursor: string | null;
  hasMore: boolean;
};

export type BidResult = {
  bidId: string;
  auctionId: string;
  bidderUserId: string;
  amountClp: number;
  serverSequence: number;
  fundsReservationId: string;
  reservationStrategy: string;
  timerReset: boolean;
  replayed: boolean;
  auction: AuctionView;
};

export type CloseNowResult = {
  auction: AuctionView;
  appointmentId: string;
  bidId: string;
  replayed: boolean;
};

export type ConfirmResult = {
  appointmentId: string;
  sessionId: string;
  videoRoomId: string;
  status: string;
  replayed: boolean;
};

export type SessionView = {
  id: string;
  appointmentId: string;
  hostUserId: string;
  bidderUserId: string;
  status: string;
  durationMinutes: number;
  agreedAmountClp: number;
  freeStartedAt: string | null;
  freeEndsAt: string | null;
  paidStartedAt: string | null;
  endedAt: string | null;
  billableSeconds: number;
  lockVersion: number;
  videoRoomId: string;
  videoStatus: string;
  joinDeadline: string | null;
  persistentRecordingEnabled?: boolean;
};

export type JoinResult = {
  sessionId: string;
  videoRoomId: string;
  participantRole: string;
  readyParticipants: number;
  sessionStatus: string;
  freeEndsAt: string | null;
  turn?: {
    urls: string[];
    username: string;
    credential: string;
    expiresAt: string;
  } | null;
};

export type WebRtcTurnConfig = {
  urls: string[];
  username: string;
  credential: string;
  expiresAt: string;
};

export type WebRtcConfig = {
  sessionId: string;
  videoRoomId: string;
  participantRole: 'HOST' | 'BIDDER';
  initiator: boolean;
  persistentRecordingEnabled: boolean;
  turn: WebRtcTurnConfig;
};

export type WebRtcSignalInput = {
  type: 'OFFER' | 'ANSWER' | 'ICE_CANDIDATE' | 'ICE_COMPLETE';
  sdp?: string | null;
  candidate?: string | null;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
};

export type WebRtcSignal = WebRtcSignalInput & {
  sequence: number;
  createdAt: string;
};

export type WebRtcSignalBatch = {
  nextAfter: number;
  signals: WebRtcSignal[];
};

export type ReconnectState = {
  sessionId?: string;
  sessionStatus?: string;
  interruptionStatus?: string | null;
  reconnectDeadline?: string | null;
  mediaRecoveredAt?: string | null;
  resumeAcceptances?: number;
  [key: string]: unknown;
};

export type FinishResult = {
  session: SessionView;
  billableSeconds: number;
  proportionalSettlementNeedsRoundingPolicy: boolean;
  settlementState: string;
};

export type BalanceView = {
  pendingClp: number;
  availableClp: number;
  heldForReviewClp: number;
  paidOutClp: number;
};

export type SafetyReportResult = {
  id: string;
  targetUserId: string;
  status: string;
};

export type BlockState = {
  userId: string;
  blocked: boolean;
};

export type AdminRow = Record<string, unknown>;

export type AdminAuctionBundle = {
  auction: AdminRow;
  bids: AdminRow[];
};

export type AdminSessionBundle = {
  session: AdminRow;
  segments: AdminRow[];
  participants: AdminRow[];
  incidents: AdminRow[];
};

export type ApiProblem = {
  code?: string;
  message?: string;
  detail?: string;
  status?: number;
};
