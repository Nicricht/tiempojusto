import { useState } from 'react';
import { tiempoJustoApi, TiempoJustoApiError } from '../lib/api';
import type { ReconnectState, SessionView } from '../lib/types';
import { OnlineVideoPanel } from './OnlineVideoPanel';

function errorText(error: unknown): string {
  if (error instanceof TiempoJustoApiError) {
    return error.problem?.code ? `${error.problem.code}: ${error.message}` : error.message;
  }
  return error instanceof Error ? error.message : 'Ocurrió un error inesperado.';
}

export default function OnlineVideoPage() {
  const [sessionId, setSessionId] = useState(() => window.localStorage.getItem('tj.sessionId') ?? '');
  const [session, setSession] = useState<SessionView | null>(null);
  const [reconnect, setReconnect] = useState<ReconnectState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function run(operation: () => Promise<void>): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await operation();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function load(): Promise<void> {
    const id = sessionId.trim();
    if (!id) return;
    await run(async () => {
      const next = await tiempoJustoApi.getSession(id);
      setSession(next);
      window.localStorage.setItem('tj.sessionId', id);
    });
  }

  async function acceptPaid(): Promise<void> {
    if (!session) return;
    await run(async () => setSession(await tiempoJustoApi.acceptPaid(session.id)));
  }

  async function resume(): Promise<void> {
    if (!session) return;
    await run(async () => setReconnect(await tiempoJustoApi.acceptResume(session.id)));
  }

  async function finish(): Promise<void> {
    if (!session) return;
    await run(async () => {
      const result = await tiempoJustoApi.finishSession(session.id);
      setSession(result.session);
    });
  }

  return (
    <main className="page">
      <section className="page-heading">
        <div>
          <span className="status status-ok">WebRTC / TURN</span>
          <h1>Sesión ONLINE real</h1>
          <p>Dos participantes autenticados usan el mismo Session UUID. La cámara es obligatoria y el backend conserva autoridad sobre FREE, cobro y reconnect.</p>
        </div>
      </section>

      <section className="control-card">
        <label className="field grow">
          Session UUID
          <input value={sessionId} onChange={(event) => setSessionId(event.target.value)} placeholder="UUID" />
        </label>
        <button className="button button-secondary" disabled={busy || !sessionId.trim()} onClick={load}>Cargar sesión</button>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      {session && (
        <>
          <section className="session-stage">
            <div><small>Estado</small><strong>{session.status}</strong></div>
            <div><small>Media</small><strong>{session.videoStatus}</strong></div>
          </section>

          <OnlineVideoPanel
            sessionId={session.id}
            sessionStatus={session.status}
            onSessionChanged={setSession}
            onReconnectChanged={setReconnect}
          />

          <section className="action-grid">
            <button className="button button-primary" disabled={busy} onClick={acceptPaid}>Aceptar sesión pagada</button>
            <button className="button button-secondary" disabled={busy} onClick={resume}>Aceptar reanudación</button>
            <button className="button button-danger" disabled={busy} onClick={finish}>Terminar sesión</button>
          </section>

          {reconnect && (
            <section className="panel reconnect-panel">
              <h2>Reconnect</h2>
              <pre>{JSON.stringify(reconnect, null, 2)}</pre>
              <p className="hint">Recuperar media no reactiva billing por sí solo. Se mantiene el consentimiento bilateral.</p>
            </section>
          )}
        </>
      )}
    </main>
  );
}
