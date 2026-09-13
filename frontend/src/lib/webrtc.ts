import { tiempoJustoApi } from './api';
import type { WebRtcSignal } from './types';

export type WebRtcState = 'idle' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';

export type WebRtcHooks = {
  onLocalStream?: (stream: MediaStream | null) => void;
  onRemoteStream?: (stream: MediaStream | null) => void;
  onState?: (state: WebRtcState) => void;
  onMediaHealth?: (flowing: boolean) => void;
  onError?: (error: Error) => void;
};

const POLL_MS = 1000;
const FORCE_RELAY = import.meta.env.VITE_TJ_WEBRTC_FORCE_RELAY === 'true';

export class TiempoJustoWebRtcSession {
  private peer: RTCPeerConnection | null = null;
  private local: MediaStream | null = null;
  private remote: MediaStream | null = null;
  private timer: number | null = null;
  private after = 0;
  private closed = false;
  private pendingIce: RTCIceCandidateInit[] = [];

  constructor(private readonly sessionId: string, private readonly hooks: WebRtcHooks = {}) {}

  async start(): Promise<void> {
    const config = await tiempoJustoApi.getWebRtcConfig(this.sessionId);
    if (config.persistentRecordingEnabled) throw new Error('ONLINE privado no permite grabación persistente.');

    this.local = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
    const camera = this.local.getVideoTracks()[0];
    if (!camera || camera.readyState !== 'live') throw new Error('La cámara es obligatoria para entrar.');
    this.hooks.onLocalStream?.(this.local);

    this.peer = new RTCPeerConnection({
      iceServers: [{ urls: config.turn.urls, username: config.turn.username, credential: config.turn.credential }],
      iceTransportPolicy: FORCE_RELAY ? 'relay' : 'all',
    });
    this.remote = new MediaStream();
    this.hooks.onRemoteStream?.(this.remote);
    this.hooks.onState?.('connecting');

    this.local.getTracks().forEach((track) => this.peer?.addTrack(track, this.local!));
    camera.addEventListener('ended', () => this.hooks.onMediaHealth?.(false));
    camera.addEventListener('mute', () => this.hooks.onMediaHealth?.(false));
    camera.addEventListener('unmute', () => this.emitHealth());

    this.peer.ontrack = (event) => {
      const target = this.remote;
      if (!target) return;
      const tracks = event.streams[0]?.getTracks() ?? [event.track];
      tracks.forEach((track) => {
        if (!target.getTracks().some((item) => item.id === track.id)) target.addTrack(track);
      });
      this.hooks.onRemoteStream?.(target);
      this.emitHealth();
    };

    this.peer.onconnectionstatechange = () => {
      const state = this.peer?.connectionState;
      if (state === 'connected') this.hooks.onState?.('connected');
      else if (state === 'disconnected') this.hooks.onState?.('disconnected');
      else if (state === 'failed') this.hooks.onState?.('failed');
      else if (state === 'closed') this.hooks.onState?.('closed');
      else this.hooks.onState?.('connecting');
      this.emitHealth();
    };

    this.peer.onicecandidate = (event) => {
      void tiempoJustoApi.sendWebRtcSignal(this.sessionId, event.candidate ? {
        type: 'ICE_CANDIDATE',
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      } : { type: 'ICE_COMPLETE' }).catch((error) => this.report(error));
    };

    this.poll();
    if (config.initiator) {
      const offer = await this.peer.createOffer();
      await this.peer.setLocalDescription(offer);
      await tiempoJustoApi.sendWebRtcSignal(this.sessionId, { type: 'OFFER', sdp: offer.sdp ?? '' });
    }
  }

  isMediaFlowing(): boolean {
    const camera = this.local?.getVideoTracks()[0];
    return Boolean(this.peer?.connectionState === 'connected' && camera && camera.readyState === 'live' && camera.enabled && !camera.muted);
  }

  async close(): Promise<void> {
    this.closed = true;
    if (this.timer != null) window.clearTimeout(this.timer);
    this.peer?.close();
    this.local?.getTracks().forEach((track) => track.stop());
    this.remote?.getTracks().forEach((track) => track.stop());
    this.peer = null;
    this.local = null;
    this.remote = null;
    this.hooks.onLocalStream?.(null);
    this.hooks.onRemoteStream?.(null);
    this.hooks.onMediaHealth?.(false);
    this.hooks.onState?.('closed');
  }

  private poll(): void {
    const run = async () => {
      if (this.closed) return;
      try {
        const batch = await tiempoJustoApi.pollWebRtcSignals(this.sessionId, this.after);
        this.after = batch.nextAfter;
        for (const signal of batch.signals) await this.handle(signal);
      } catch (cause) {
        this.report(cause);
      }
      if (!this.closed) this.timer = window.setTimeout(run, POLL_MS);
    };
    void run();
  }

  private async handle(signal: WebRtcSignal): Promise<void> {
    const peer = this.peer;
    if (!peer) return;
    if (signal.type === 'OFFER' && signal.sdp) {
      await peer.setRemoteDescription({ type: 'offer', sdp: signal.sdp });
      await this.flushIce();
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      await tiempoJustoApi.sendWebRtcSignal(this.sessionId, { type: 'ANSWER', sdp: answer.sdp ?? '' });
    } else if (signal.type === 'ANSWER' && signal.sdp && peer.signalingState === 'have-local-offer') {
      await peer.setRemoteDescription({ type: 'answer', sdp: signal.sdp });
      await this.flushIce();
    } else if (signal.type === 'ICE_CANDIDATE' && signal.candidate) {
      const ice = { candidate: signal.candidate, sdpMid: signal.sdpMid ?? undefined, sdpMLineIndex: signal.sdpMLineIndex ?? undefined };
      if (peer.remoteDescription) await peer.addIceCandidate(ice);
      else this.pendingIce.push(ice);
    } else if (signal.type === 'ICE_COMPLETE' && peer.remoteDescription) {
      await peer.addIceCandidate(null);
    }
  }

  private async flushIce(): Promise<void> {
    if (!this.peer?.remoteDescription) return;
    for (const candidate of this.pendingIce.splice(0)) await this.peer.addIceCandidate(candidate);
  }

  private emitHealth(): void {
    this.hooks.onMediaHealth?.(this.isMediaFlowing());
  }

  private report(cause: unknown): void {
    this.hooks.onError?.(cause instanceof Error ? cause : new Error(String(cause)));
  }
}
