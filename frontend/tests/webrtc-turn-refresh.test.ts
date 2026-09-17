import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getWebRtcConfig: vi.fn(),
  pollWebRtcSignals: vi.fn(),
  sendWebRtcSignal: vi.fn(),
}));

vi.mock('../src/lib/api', () => ({
  tiempoJustoApi: apiMocks,
}));

import { TiempoJustoWebRtcSession } from '../src/lib/webrtc';

class FakeTrack extends EventTarget {
  readonly id = crypto.randomUUID();
  readyState: MediaStreamTrackState = 'live';
  enabled = true;
  muted = false;
  stop = vi.fn(() => { this.readyState = 'ended'; });
}

class FakeMediaStream {
  private readonly tracks: FakeTrack[];

  constructor(tracks: FakeTrack[] = []) {
    this.tracks = [...tracks];
  }

  getVideoTracks(): FakeTrack[] { return this.tracks; }
  getTracks(): FakeTrack[] { return this.tracks; }
  addTrack(track: FakeTrack): void { this.tracks.push(track); }
}

let latestPeer: FakePeerConnection | null = null;

class FakePeerConnection {
  connectionState: RTCPeerConnectionState = 'new';
  signalingState: RTCSignalingState = 'stable';
  remoteDescription: RTCSessionDescription | null = null;
  ontrack: ((event: RTCTrackEvent) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
  setConfiguration = vi.fn();
  addTrack = vi.fn();
  addIceCandidate = vi.fn().mockResolvedValue(undefined);
  setRemoteDescription = vi.fn().mockResolvedValue(undefined);
  setLocalDescription = vi.fn().mockResolvedValue(undefined);
  createAnswer = vi.fn().mockResolvedValue({ type: 'answer', sdp: 'answer' });
  createOffer = vi.fn().mockResolvedValue({ type: 'offer', sdp: 'offer' });
  close = vi.fn(() => { this.connectionState = 'closed'; });

  constructor(_configuration?: RTCConfiguration) {
    latestPeer = this;
  }
}

function config(username: string, expiresAt: string) {
  return {
    sessionId: 'session-1',
    videoRoomId: 'room-1',
    participantRole: 'BIDDER' as const,
    initiator: false,
    persistentRecordingEnabled: false,
    turn: {
      urls: ['turn:turn.example.test:3478'],
      username,
      credential: `credential-${username}`,
      expiresAt,
    },
  };
}

describe('TiempoJustoWebRtcSession TURN credentials', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-16T20:00:00Z'));
    latestPeer = null;
    apiMocks.getWebRtcConfig.mockReset();
    apiMocks.pollWebRtcSignals.mockReset();
    apiMocks.sendWebRtcSignal.mockReset();

    const localTrack = new FakeTrack();
    const localStream = new FakeMediaStream([localTrack]);
    Object.defineProperty(window.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn().mockResolvedValue(localStream) },
    });
    vi.stubGlobal('MediaStream', FakeMediaStream);
    vi.stubGlobal('RTCPeerConnection', FakePeerConnection);
    apiMocks.pollWebRtcSignals.mockImplementation(() => new Promise(() => undefined));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('refreshes short-lived TURN credentials before expiry without restarting the session', async () => {
    apiMocks.getWebRtcConfig
      .mockResolvedValueOnce(config('turn-1', '2026-09-16T20:02:00Z'))
      .mockResolvedValueOnce(config('turn-2', '2026-09-16T20:12:00Z'));

    const session = new TiempoJustoWebRtcSession('session-1');
    await session.start();

    expect(apiMocks.getWebRtcConfig).toHaveBeenCalledTimes(1);
    expect(latestPeer).not.toBeNull();

    await vi.advanceTimersByTimeAsync(61_000);

    expect(apiMocks.getWebRtcConfig).toHaveBeenCalledTimes(2);
    expect(latestPeer?.setConfiguration).toHaveBeenCalledWith(expect.objectContaining({
      iceServers: [expect.objectContaining({ username: 'turn-2' })],
    }));

    await session.close();
  });
});
