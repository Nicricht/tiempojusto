import { useEffect, useMemo, useState } from 'react';
import {
  getDevActorId,
  setDevActorId,
  tiempoJustoApi,
  TiempoJustoApiError,
} from './lib/api';
import type {
  AuctionView,
  BalanceView,
  CloseNowResult,
  FinishResult,
  ReconnectState,
  SessionView,
} from './lib/types';

type View = 'inicio' | 'auction' | 'session' | 'wallet';

const money = new Intl.NumberFormat('es-CL', {
  style: 'currency',
  currency: 'CLP',
  maximumFractionDigits: 0,
});

function errorText(error: unknown): string {
  if (error instanceof TiempoJustoApiError) {
    return error.problem?.code ? `${error.problem.code}: ${error.message}` : error.message;
  }
  return error instanceof Error ? error.message : 'Ocurrió un error inesperado.';
}

function secondsUntil(iso: string | null | undefined, now: number): number | null {
  if (!iso) return null;
  return Math.max(0, Math.floor((new Date(iso).getTime() - now) / 1000));
}

function clock(seconds: number | null): string {
  if (seconds == null) return '--:--';
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

function Header({ view, onNavigate }: { view: View; onNavigate: (view: View) => void }) {
  return (
    <header className="topbar">
      <button className="brand" onClick={() => onNavigate('inicio')} aria-label="Ir al inicio">
        <span className="brand-mark">TJ</span>
        <span>
          <strong>TiempoJusto</strong>
          <small>MVP ONLINE</small>
        </span>
      </button>
      <nav className="topnav" aria-label="Navegación principal">
        <button className={view === 'auction' ? 'active' : ''} onClick={() => onNavigate('auction')}>Auction</button>
        <button className={view === 'session' ? 'active' : ''} onClick={() => onNavigate('session')}>Sesión</button>
        <button className={view === 'wallet' ? 'active' : ''} onClick={() => onNavigate('wallet')}>Saldo</button>
      </nav>
    </header>
  );
}

function Status({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: 'neutral' | 'ok' | 'warn' | 'danger' }) {
  return <span className={`status status-${tone}`}>{children}</span>;
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">⌁</div>
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  );
}

function DevConnection() {
  const [actorId, setActor] = useState(() => getDevActorId());
  if (!tiempoJustoApi.devMode) return null;

  return (
    <details className="dev-panel">
      <summary>Conexión de desarrollo</summary>
      <p>Solo aparece con <code>VITE_TJ_DEV_MODE=true</code>. Nunca debe habilitarse en producción.</p>
      <label>
        Actor UUID
        <input
          value={actorId}
          onChange={(event) => setActor(event.target.value)}
          placeholder="UUID del HOST o BIDDER"
          autoComplete="off"
        />
      </label>
      <button
        className="button button-secondary"
        onClick={() => {
          setDevActorId(actorId);
          setActor(getDevActorId());
        }}
      >
        Guardar actor local
      </button>
      <small>API: {tiempoJustoApi.baseUrl}</small>
    </details>
  );
}

function Home({ onNavigate }: { onNavigate: (view: View) => void }) {
  return (
    <main className="page home-page">
      <section className="hero">
        <div>
          <Status tone="ok">Vertical ONLINE</Status>
          <h1>Tu tiempo tiene valor. El sistema cuida el reloj.</h1>
          <p>
            Esta primera interfaz conecta con los contratos reales de Auction, sesión ONLINE, reconnect y Wallet.
            El backend sigue siendo la única autoridad para tiempo, elegibilidad y dinero.
          </p>
          <div className="hero-actions">
            <button className="button button-primary" onClick={() => onNavigate('auction')}>Entrar a Auction</button>
            <button className="button button-secondary" onClick={() => onNavigate('session')}>Abrir sesión</button>
          </div>
        </div>
        <div className="hero-card" aria-label="Reglas esenciales de sesión online">
          <span>ONLINE AHORA</span>
          <strong>2 min</strong>
          <p>FREE_ONLINE antes del cobro.</p>
          <div className="hero-rule"><b>5 s</b><span>tolerancia técnica</span></div>
          <div className="hero-rule"><b>2 min</b><span>reconexión</span></div>
          <div className="hero-rule"><b>80/20</b><span>split de sesión</span></div>
        </div>
      </section>

      <section className="feature-grid" aria-label="Módulos del MVP">
        <button className="feature-card" onClick={() => onNavigate('auction')}>
          <span className="feature-number">01</span>
          <strong>Auction</strong>
          <p>Pozo, pujas, anti-sniping y Ganar Ahora con estado de servidor.</p>
        </button>
        <button className="feature-card" onClick={() => onNavigate('session')}>
          <span className="feature-number">02</span>
          <strong>Sesión ONLINE</strong>
          <p>FREE, consentimiento bilateral, billing y reconexión sin cobro fantasma.</p>
        </button>
        <button className="feature-card" onClick={() => onNavigate('wallet')}>
          <span className="feature-number">03</span>
          <strong>Wallet</strong>
          <p>PENDING, AVAILABLE, holds objetivos y trazabilidad financiera.</p>
        </button>
      </section>

      <section className="guardrail">
        <div>
          <strong>Regla de interfaz</strong>
          <p>Los contadores del navegador son visuales. Nunca autorizan cobro, adjudicación ni payout.</p>
        </div>
        <Status>server-authoritative</Status>
      </section>

      <DevConnection />
    </main>
  );
}

function AuctionPage() {
  const now = useNow();
  const [auctionId, setAuctionId] = useState(() => window.localStorage.getItem('tj.auctionId') ?? '');
  const [auction, setAuction] = useState<AuctionView | null>(null);
  const [closeResult, setCloseResult] = useState<CloseNowResult | null>(null);
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const remaining = useMemo(() => secondsUntil(auction?.effectiveEndAt, now), [auction?.effectiveEndAt, now]);

  async function load(): Promise<void> {
    if (!auctionId.trim()) return;
    setBusy(true);
    setError('');
    try {
      const next = await tiempoJustoApi.getAuction(auctionId.trim());
      setAuction(next);
      setAmount(String(next.nextActionableAmountClp));
      window.localStorage.setItem('tj.auctionId', next.id);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function bid(): Promise<void> {
    if (!auction) return;
    const amountClp = Number(amount);
    if (!Number.isInteger(amountClp) || amountClp <= 0) {
      setError('Ingresa un monto CLP entero válido.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await tiempoJustoApi.placeBid(auction.id, amountClp);
      setAuction(result.auction);
      setAmount(String(result.auction.nextActionableAmountClp));
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function closeNow(): Promise<void> {
    if (!auction) return;
    setBusy(true);
    setError('');
    try {
      const result = await tiempoJustoApi.closeNow(auction.id);
      setCloseResult(result);
      setAuction(result.auction);
      window.localStorage.setItem('tj.appointmentId', result.appointmentId);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <section className="page-heading">
        <div>
          <Status>Auction · 15 min</Status>
          <h1>Auction en tiempo real</h1>
          <p>El reloj mostrado aquí refleja <code>effectiveEndAt</code>. El navegador no extiende ni cierra la Auction.</p>
        </div>
      </section>

      <section className="control-card">
        <label className="field grow">
          Auction UUID
          <input value={auctionId} onChange={(event) => setAuctionId(event.target.value)} placeholder="UUID" />
        </label>
        <button className="button button-secondary" disabled={busy || !auctionId.trim()} onClick={load}>
          {busy ? 'Consultando…' : 'Cargar'}
        </button>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      {!auction ? (
        <EmptyState title="Carga una Auction" body="Usa un UUID existente del backend para ver el estado autoritativo y ejecutar acciones." />
      ) : (
        <>
          <section className="auction-hero">
            <div>
              <small>Tiempo restante visual</small>
              <strong className="timer">{clock(remaining)}</strong>
              <span>fin servidor · {new Date(auction.effectiveEndAt).toLocaleTimeString('es-CL')}</span>
            </div>
            <div className="auction-money">
              <small>Pozo actual</small>
              <strong>{money.format(auction.currentAmountClp)}</strong>
              <Status tone={auction.status === 'OPEN' ? 'ok' : 'warn'}>{auction.status}</Status>
            </div>
          </section>

          <section className="two-column">
            <article className="panel">
              <h2>Tu acción</h2>
              <label className="field">
                Monto de la puja
                <input inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value.replace(/\D/g, ''))} />
              </label>
              <button className="button button-primary full" disabled={busy || auction.status !== 'OPEN'} onClick={bid}>
                Pujar {amount ? money.format(Number(amount)) : ''}
              </button>
              <p className="hint">Siguiente monto informado por backend: {money.format(auction.nextActionableAmountClp)}</p>
            </article>

            <article className="panel">
              <h2>Ganar Ahora</h2>
              <div className="big-value">{auction.closeNowAmountClp == null ? 'No disponible' : money.format(auction.closeNowAmountClp)}</div>
              <button className="button button-dark full" disabled={busy || auction.closeNowAmountClp == null || auction.status !== 'OPEN'} onClick={closeNow}>
                Ejecutar Ganar Ahora
              </button>
              <p className="hint">La confirmación del Winner mantiene su ventana de 3 minutos en backend.</p>
            </article>
          </section>

          <section className="data-strip">
            <div><small>Modalidad</small><strong>{auction.modality}</strong></div>
            <div><small>Duración</small><strong>{auction.durationMinutes} min</strong></div>
            <div><small>Lock</small><strong>v{auction.lockVersion}</strong></div>
            <div><small>Winner</small><strong>{auction.winnerUserId ? 'adjudicado' : 'pendiente'}</strong></div>
          </section>

          {closeResult && (
            <section className="success-card">
              <Status tone="ok">Adjudicación creada</Status>
              <h2>Appointment listo para confirmar</h2>
              <code>{closeResult.appointmentId}</code>
              <p>Se guardó localmente para continuar el recorrido. La confirmación debe ejecutarla el BIDDER ganador.</p>
            </section>
          )}
        </>
      )}

      <DevConnection />
    </main>
  );
}

function SessionPage() {
  const now = useNow();
  const [appointmentId, setAppointmentId] = useState(() => window.localStorage.getItem('tj.appointmentId') ?? '');
  const [sessionId, setSessionId] = useState(() => window.localStorage.getItem('tj.sessionId') ?? '');
  const [session, setSession] = useState<SessionView | null>(null);
  const [reconnect, setReconnect] = useState<ReconnectState | null>(null);
  const [finish, setFinish] = useState<FinishResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const freeRemaining = useMemo(() => secondsUntil(session?.freeEndsAt, now), [session?.freeEndsAt, now]);
  const joinRemaining = useMemo(() => secondsUntil(session?.joinDeadline, now), [session?.joinDeadline, now]);

  async function action<T>(operation: () => Promise<T>, done: (value: T) => void): Promise<void> {
    setBusy(true);
    setError('');
    try {
      done(await operation());
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(): Promise<void> {
    if (!appointmentId.trim()) return;
    await action(
      () => tiempoJustoApi.confirmWinner(appointmentId.trim()),
      (result) => {
        setSessionId(result.sessionId);
        window.localStorage.setItem('tj.sessionId', result.sessionId);
      },
    );
  }

  async function load(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(() => tiempoJustoApi.getSession(sessionId.trim()), setSession);
    window.localStorage.setItem('tj.sessionId', sessionId.trim());
  }

  async function join(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(
      () => tiempoJustoApi.joinSession(sessionId.trim()),
      async () => setSession(await tiempoJustoApi.getSession(sessionId.trim())),
    );
  }

  async function acceptPaid(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(() => tiempoJustoApi.acceptPaid(sessionId.trim()), setSession);
  }

  async function reconnectState(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(() => tiempoJustoApi.getReconnectState(sessionId.trim()), setReconnect);
  }

  async function resume(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(() => tiempoJustoApi.acceptResume(sessionId.trim()), setReconnect);
  }

  async function finishNow(): Promise<void> {
    if (!sessionId.trim()) return;
    await action(
      () => tiempoJustoApi.finishSession(sessionId.trim()),
      (result) => {
        setFinish(result);
        setSession(result.session);
      },
    );
  }

  return (
    <main className="page">
      <section className="page-heading">
        <div>
          <Status tone="ok">ONLINE AHORA</Status>
          <h1>Sesión segura</h1>
          <p>Join, FREE_ONLINE, consentimiento, PAID_ACTIVE y reconnect usan el estado persistido del backend.</p>
        </div>
      </section>

      <section className="control-card control-stack">
        <div className="control-row">
          <label className="field grow">
            Appointment UUID del Winner
            <input value={appointmentId} onChange={(event) => setAppointmentId(event.target.value)} placeholder="UUID" />
          </label>
          <button className="button button-secondary" disabled={busy || !appointmentId.trim()} onClick={confirm}>Confirmar Winner</button>
        </div>
        <div className="control-row">
          <label className="field grow">
            Session UUID
            <input value={sessionId} onChange={(event) => setSessionId(event.target.value)} placeholder="UUID" />
          </label>
          <button className="button button-secondary" disabled={busy || !sessionId.trim()} onClick={load}>Cargar sesión</button>
        </div>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      {!session ? (
        <EmptyState title="Carga o confirma una sesión" body="El BIDDER puede confirmar el Appointment y luego ambos participantes usan el mismo Session UUID." />
      ) : (
        <>
          <section className="session-stage">
            <div>
              <small>Estado de sesión</small>
              <div className="state-line"><strong>{session.status}</strong><Status tone={session.status === 'PAID_ACTIVE' ? 'ok' : 'neutral'}>{session.videoStatus}</Status></div>
            </div>
            <div className="session-clock">
              <small>{session.status === 'FREE_ACTIVE' ? 'FREE restante' : 'Join restante'}</small>
              <strong>{session.status === 'FREE_ACTIVE' ? clock(freeRemaining) : clock(joinRemaining)}</strong>
            </div>
          </section>

          <section className="session-grid">
            <article className="video-placeholder large-video">
              <span>Media remota</span>
              <strong>{session.videoStatus}</strong>
              <small>WebRTC real sigue siendo Gate D del MVP Production V1.</small>
            </article>
            <article className="video-placeholder self-video">
              <span>Tu cámara</span>
              <strong>obligatoria</strong>
            </article>
          </section>

          <section className="action-grid">
            <button className="button button-secondary" disabled={busy} onClick={join}>Entrar con cámara válida</button>
            <button className="button button-primary" disabled={busy} onClick={acceptPaid}>Aceptar sesión pagada</button>
            <button className="button button-secondary" disabled={busy} onClick={reconnectState}>Consultar reconnect</button>
            <button className="button button-secondary" disabled={busy} onClick={resume}>Aceptar reanudación</button>
            <button className="button button-danger" disabled={busy} onClick={finishNow}>Terminar sesión</button>
          </section>

          <section className="data-strip">
            <div><small>Facturable</small><strong>{session.billableSeconds} s</strong></div>
            <div><small>Acuerdo</small><strong>{money.format(session.agreedAmountClp)}</strong></div>
            <div><small>Duración</small><strong>{session.durationMinutes} min</strong></div>
            <div><small>Grabación</small><strong>{session.persistentRecordingEnabled === true ? 'revisar' : 'no persistente'}</strong></div>
          </section>

          {reconnect && (
            <section className="panel reconnect-panel">
              <div>
                <Status tone="warn">Reconnect state</Status>
                <h2>{String(reconnect.sessionStatus ?? session.status)}</h2>
              </div>
              <pre>{JSON.stringify(reconnect, null, 2)}</pre>
              <p className="hint">Recuperar media nunca equivale por sí solo a reanudar billing.</p>
            </section>
          )}

          {finish && (
            <section className="success-card">
              <Status tone="ok">Sesión finalizada</Status>
              <h2>{finish.billableSeconds} segundos facturables</h2>
              <p>Settlement: <strong>{finish.settlementState}</strong></p>
              {finish.proportionalSettlementNeedsRoundingPolicy && (
                <p className="warning-text">Bloqueado por PENDING_ROUNDING_POLICY. La interfaz no inventa redondeo CLP.</p>
              )}
            </section>
          )}
        </>
      )}

      <DevConnection />
    </main>
  );
}

function WalletPage() {
  const [balance, setBalance] = useState<BalanceView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function load(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      setBalance(await tiempoJustoApi.getBalance());
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <section className="page-heading row-heading">
        <div>
          <Status>Wallet</Status>
          <h1>Saldo y payout</h1>
          <p>El balance se consulta directamente desde FinanceReadService para el actor autenticado.</p>
        </div>
        <button className="button button-primary" disabled={busy} onClick={load}>{busy ? 'Actualizando…' : 'Actualizar saldo'}</button>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      {!balance ? (
        <EmptyState title="Consulta tu saldo" body="PENDING y AVAILABLE son estados distintos. El hold mínimo de 60 minutos lo hace cumplir el backend." />
      ) : (
        <>
          <section className="balance-grid">
            <article className="balance-card balance-main">
              <small>Disponible</small>
              <strong>{money.format(balance.availableClp)}</strong>
              <span>elegible para payout según reglas vigentes</span>
            </article>
            <article className="balance-card">
              <small>Pendiente</small>
              <strong>{money.format(balance.pendingClp)}</strong>
              <span>hold financiero en curso</span>
            </article>
            <article className="balance-card">
              <small>En revisión</small>
              <strong>{money.format(balance.heldForReviewClp)}</strong>
              <span>objective hold activo</span>
            </article>
            <article className="balance-card">
              <small>Pagado</small>
              <strong>{money.format(balance.paidOutClp)}</strong>
              <span>histórico liquidado</span>
            </article>
          </section>

          <section className="guardrail">
            <div>
              <strong>Hold mínimo de 60 minutos</strong>
              <p>La UI solo representa el estado. No puede adelantar AVAILABLE ni mutar el ledger.</p>
            </div>
            <Status tone="ok">Finance authoritative</Status>
          </section>
        </>
      )}

      <DevConnection />
    </main>
  );
}

export default function App() {
  const [view, setView] = useState<View>('inicio');

  return (
    <div className="app-shell">
      <Header view={view} onNavigate={setView} />
      {view === 'inicio' && <Home onNavigate={setView} />}
      {view === 'auction' && <AuctionPage />}
      {view === 'session' && <SessionPage />}
      {view === 'wallet' && <WalletPage />}
      <footer className="footer">
        <span>TiempoJusto · MVP Production V1</span>
        <span>Reglas críticas siempre server-authoritative</span>
      </footer>
    </div>
  );
}
