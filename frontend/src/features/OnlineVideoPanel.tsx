import { useEffect, useRef, useState } from 'react';
import { tiempoJustoApi } from '../lib/api';
import { TiempoJustoWebRtcSession, type WebRtcState } from '../lib/webrtc';
import type { ReconnectState, SessionView } from '../lib/types';

type Props = {
  sessionId: string;
  sessionStatus: string;
  onSessionChanged: (session: SessionView) => void;
  onReconnectChanged: (state: ReconnectState) => void;
};

export function OnlineVideoPanel({ sessionId, sessionStatus, onSessionChanged, onReconnectChanged }: Props) {
  const localVideo = useRef<HTMLVideoElement | null>(null);
  const remoteVideo = useRef<HTMLVideoElement | null>(null);
  const rtc = useRef<TiempoJustoWebRtcSession | null>(null);
  const joined = useRef(false);
  const [state, setState] = useState<WebRtcState>('idle');
  const [mediaFlowing, setMediaFlowing] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => () => { void rtc.current?.close(); }, []);

  useEffect(() => {
    if (!rtc.current || !['PAID_ACTIVE', 'RECONNECTING'].includes(sessionStatus)) return;
    const beat = async () => {
      try {
        const current = await tiempoJustoApi.mediaSignal(sessionId, rtc.current?.isMediaFlowing() === true);
        onReconnectChanged(current);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    };
    void beat();
    const timer = window.setInterval(beat, 3_000);
    return () => window.clearInterval(timer);
  }, [sessionId, sessionStatus, onReconnectChanged]);

  async function start(): Promise<void> {
    if (rtc.current) return;
    setError('');
    const connection = new TiempoJustoWebRtcSession(sessionId, {
      onLocalStream(stream) {
        if (localVideo.current) localVideo.current.srcObject = stream;
      },
      onRemoteStream(stream) {
        if (remoteVideo.current) remoteVideo.current.srcObject = stream;
      },
      onState(next) {
        setState(next);
        if (next === 'connected' && !joined.current) {
          joined.current = true;
          void tiempoJustoApi.joinSession(sessionId)
            .then(() => tiempoJustoApi.getSession(sessionId))
            .then(onSessionChanged)
            .catch((cause) => {
              joined.current = false;
              setError(cause instanceof Error ? cause.message : String(cause));
            });
        }
      },
      onMediaHealth: setMediaFlowing,
      onError(cause) {
        setError(cause.message);
      },
    });
    rtc.current = connection;
    try {
      await connection.start();
    } catch (cause) {
      rtc.current = null;
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  async function stop(): Promise<void> {
    const active = rtc.current;
    rtc.current = null;
    joined.current = false;
    await active?.close();
    setState('closed');
    setMediaFlowing(false);
  }

  return (
    <section className="webrtc-panel" data-testid="webrtc-panel">
      <div className="session-grid">
        <article className="video-surface large-video">
          <video ref={remoteVideo} autoPlay playsInline data-testid="remote-video" />
          <div className="video-label">Media remota · {state}</div>
        </article>
        <article className="video-surface self-video">
          <video ref={localVideo} autoPlay playsInline muted data-testid="local-video" />
          <div className="video-label">Tu cámara · {mediaFlowing ? 'válida' : 'sin flujo'}</div>
        </article>
      </div>
      <div className="action-grid">
        <button className="button button-secondary" disabled={Boolean(rtc.current)} onClick={start}>Iniciar cámara y WebRTC</button>
        <button className="button button-danger" disabled={!rtc.current} onClick={stop}>Cerrar media</button>
      </div>
      {error && <div className="error-banner" role="alert">{error}</div>}
      <p className="hint">La cámara es obligatoria. El video no se graba ni se persiste. TURN usa credenciales temporales emitidas por backend.</p>
    </section>
  );
}
