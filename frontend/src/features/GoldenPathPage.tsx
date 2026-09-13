import { useEffect, useState } from 'react';
import { clearAccessToken, tiempoJustoApi, TiempoJustoApiError } from '../lib/api';
import { apiErrorText } from '../lib/errors';
import { oidc } from '../lib/oidc';
import type {
  AuctionView,
  AuthMe,
  BalanceView,
  FinishResult,
  KycStatusResult,
  ProfileView,
  ProposalView,
  ReconnectState,
  SafetyReportResult,
  SessionView,
} from '../lib/types';
import { OnlineVideoPanel } from './OnlineVideoPanel';

const money = new Intl.NumberFormat('es-CL', {
  style: 'currency',
  currency: 'CLP',
  maximumFractionDigits: 0,
});

export default function GoldenPathPage() {
  const [me, setMe] = useState<AuthMe | null>(null);
  const [kyc, setKyc] = useState<KycStatusResult | null>(null);
  const [hosts, setHosts] = useState<ProfileView[]>([]);
  const [host, setHost] = useState<ProfileView | null>(null);
  const [proposal, setProposal] = useState<ProposalView | null>(null);
  const [proposalAmount, setProposalAmount] = useState('60000');
  const [duration, setDuration] = useState(30);
  const [auctions, setAuctions] = useState<AuctionView[]>([]);
  const [auction, setAuction] = useState<AuctionView | null>(null);
  const [bidAmount, setBidAmount] = useState('');
  const [appointmentId, setAppointmentId] = useState(() => localStorage.getItem('tj.appointmentId') ?? '');
  const [sessionId, setSessionId] = useState(() => localStorage.getItem('tj.sessionId') ?? '');
  const [session, setSession] = useState<SessionView | null>(null);
  const [reconnect, setReconnect] = useState<ReconnectState | null>(null);
  const [finish, setFinish] = useState<FinishResult | null>(null);
  const [balance, setBalance] = useState<BalanceView | null>(null);
  const [reportDescription, setReportDescription] = useState('');
  const [report, setReport] = useState<SafetyReportResult | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => { void loadIdentity(); }, []);

  async function run<T>(operation: () => Promise<T>, done?: (value: T) => void): Promise<T | null> {
    setBusy(true);
    setError('');
    try {
      const result = await operation();
      done?.(result);
      return result;
    } catch (cause) {
      setError(apiErrorText(cause));
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function loadIdentity(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const identity = await tiempoJustoApi.me();
      setMe(identity);
      try {
        setKyc(await tiempoJustoApi.latestKyc());
      } catch (cause) {
        if (!(cause instanceof TiempoJustoApiError) || cause.status !== 404) throw cause;
        setKyc(null);
      }
    } catch (cause) {
      if (cause instanceof TiempoJustoApiError && cause.status === 401) {
        setMe(null);
        setKyc(null);
      } else {
        setError(apiErrorText(cause));
      }
    } finally {
      setBusy(false);
    }
  }

  async function login(): Promise<void> {
    try {
      await oidc.login('#/flow');
    } catch (cause) {
      setError(apiErrorText(cause));
    }
  }

  function logout(): void {
    oidc.logout();
    clearAccessToken();
    setMe(null);
    setKyc(null);
    setHosts([]);
    setHost(null);
  }

  async function startKyc(): Promise<void> {
    const result = await run(() => tiempoJustoApi.startKyc());
    if (result?.verificationUrl) window.location.assign(result.verificationUrl);
  }

  async function discover(): Promise<void> {
    const page = await run(() => tiempoJustoApi.discoverOnline(30));
    if (page) setHosts(page.items);
  }

  async function selectHost(next: ProfileView): Promise<void> {
    setHost(next);
    localStorage.setItem('tj.hostProfileId', next.id);
    const state = await run(() => tiempoJustoApi.getBlockState(next.userId));
    if (state) setBlocked(state.blocked);
  }

  async function createProposal(): Promise<void> {
    if (!host) return;
    const amountClp = Number(proposalAmount);
    if (!Number.isInteger(amountClp) || amountClp <= 0) {
      setError('El monto debe ser CLP entero positivo.');
      return;
    }
    const result = await run(() => tiempoJustoApi.createProposal(host.id, amountClp, duration));
    if (result) {
      setProposal(result);
      localStorage.setItem('tj.proposalId', result.id);
    }
  }

  async function loadAuctions(): Promise<void> {
    const page = await run(() => tiempoJustoApi.listAuctions(30));
    if (page) setAuctions(page.items);
  }

  function selectAuction(next: AuctionView): void {
    setAuction(next);
    setBidAmount(String(next.nextActionableAmountClp));
    localStorage.setItem('tj.auctionId', next.id);
  }

  async function bid(): Promise<void> {
    if (!auction) return;
    const amountClp = Number(bidAmount);
    const result = await run(() => tiempoJustoApi.placeBid(auction.id, amountClp));
    if (result) {
      setAuction(result.auction);
      setBidAmount(String(result.auction.nextActionableAmountClp));
    }
  }

  async function closeNow(): Promise<void> {
    if (!auction) return;
    const result = await run(() => tiempoJustoApi.closeNow(auction.id));
    if (result) {
      setAuction(result.auction);
      setAppointmentId(result.appointmentId);
      localStorage.setItem('tj.appointmentId', result.appointmentId);
    }
  }

  async function confirmWinner(): Promise<void> {
    if (!appointmentId) return;
    const result = await run(() => tiempoJustoApi.confirmWinner(appointmentId));
    if (result) {
      setSessionId(result.sessionId);
      localStorage.setItem('tj.sessionId', result.sessionId);
      const next = await run(() => tiempoJustoApi.getSession(result.sessionId));
      if (next) setSession(next);
    }
  }

  async function refreshSession(): Promise<void> {
    if (!sessionId) return;
    const result = await run(() => tiempoJustoApi.getSession(sessionId));
    if (result) setSession(result);
  }

  async function acceptPaid(): Promise<void> {
    if (!sessionId) return;
    const result = await run(() => tiempoJustoApi.acceptPaid(sessionId));
    if (result) setSession(result);
  }

  async function refreshReconnect(): Promise<void> {
    if (!sessionId) return;
    const result = await run(() => tiempoJustoApi.getReconnectState(sessionId));
    if (result) setReconnect(result);
  }

  async function acceptResume(): Promise<void> {
    if (!sessionId) return;
    const result = await run(() => tiempoJustoApi.acceptResume(sessionId));
    if (result) setReconnect(result);
    await refreshSession();
  }

  async function finishSession(): Promise<void> {
    if (!sessionId) return;
    const result = await run(() => tiempoJustoApi.finishSession(sessionId));
    if (result) {
      setFinish(result);
      setSession(result.session);
    }
  }

  async function loadBalance(): Promise<void> {
    const result = await run(() => tiempoJustoApi.getBalance());
    if (result) setBalance(result);
  }

  async function createReport(): Promise<void> {
    if (!host) return;
    if (reportDescription.trim().length < 10) {
      setError('Describe el motivo del reporte con al menos 10 caracteres.');
      return;
    }
    const result = await run(() => tiempoJustoApi.createSafetyReport(
      host.userId,
      'USER_REPORT',
      reportDescription.trim(),
      appointmentId || undefined,
    ));
    if (result) setReport(result);
  }

  async function toggleBlock(): Promise<void> {
    if (!host) return;
    const result = await run(() => blocked
      ? tiempoJustoApi.unblockUser(host.userId)
      : tiempoJustoApi.blockUser(host.userId));
    if (result) setBlocked(result.blocked);
  }

  const kycOk = kyc?.status === 'VERIFIED' && kyc.verifiedAdult;

  return (
    <main className="page">
      <section className="page-heading row-heading">
        <div>
          <span className="status status-ok">Golden Path ONLINE</span>
          <h1>De identidad a Wallet, sin IDs manuales</h1>
          <p>El navegador guía el flujo. Dinero, tiempo, elegibilidad, Auction y billing siguen siendo autoritativos en backend.</p>
        </div>
        {me ? <button className="button button-secondary" onClick={logout}>Cerrar sesión</button> : null}
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      <section className="panel">
        <h2>1. Acceso y KYC</h2>
        {!me ? (
          <div className="action-grid">
            <button className="button button-primary" disabled={busy || !oidc.configured} onClick={login}>Iniciar sesión con OIDC</button>
            <button className="button button-secondary" disabled={busy} onClick={loadIdentity}>Reintentar identidad</button>
            {!oidc.configured && !tiempoJustoApi.devMode && <p className="hint">Este entorno aún no tiene endpoints OIDC configurados.</p>}
            {tiempoJustoApi.devMode && <p className="hint">En desarrollo también puedes usar el Actor UUID del panel Core.</p>}
          </div>
        ) : (
          <div className="data-strip">
            <div><small>Public ID</small><strong>{me.publicId}</strong></div>
            <div><small>Rol</small><strong>{me.role}</strong></div>
            <div><small>Cuenta</small><strong>{me.accountStatus}</strong></div>
            <div><small>KYC</small><strong>{kycOk ? 'VERIFIED 18+' : kyc?.status ?? 'NOT_STARTED'}</strong></div>
          </div>
        )}
        {me && !kycOk && <button className="button button-primary" disabled={busy} onClick={startKyc}>Iniciar verificación KYC</button>}
      </section>

      <section className="panel">
        <div className="row-heading">
          <div><h2>2. Discovery y perfil HOST</h2><p className="hint">Solo perfiles ONLINE activos y no bloqueados.</p></div>
          <button className="button button-secondary" disabled={busy || !me} onClick={discover}>Buscar HOSTS</button>
        </div>
        <div className="feature-grid">
          {hosts.map((item) => (
            <button key={item.id} className={`feature-card ${host?.id === item.id ? 'active' : ''}`} onClick={() => void selectHost(item)}>
              <span className="feature-number">{item.publicAge ?? '18+'}</span>
              <strong>{item.displayName}</strong>
              <p>@{item.username} · ONLINE</p>
              <small>{item.bio || 'Perfil sin descripción pública.'}</small>
            </button>
          ))}
          {hosts.length === 0 && <p className="hint">Carga Discovery para ver perfiles disponibles.</p>}
        </div>
      </section>

      <section className="two-column">
        <article className="panel">
          <h2>3. Proposal</h2>
          <p>{host ? `HOST seleccionado: ${host.displayName}` : 'Selecciona un HOST en Discovery.'}</p>
          <label className="field">Monto CLP<input value={proposalAmount} inputMode="numeric" onChange={(e) => setProposalAmount(e.target.value.replace(/\D/g, ''))} /></label>
          <label className="field">Duración<select value={duration} onChange={(e) => setDuration(Number(e.target.value))}><option value={15}>15 min</option><option value={30}>30 min</option><option value={60}>60 min</option></select></label>
          <button className="button button-primary full" disabled={busy || !host || !kycOk} onClick={createProposal}>Crear Proposal</button>
          {proposal && <p className="success-card">Proposal {proposal.status} · {money.format(proposal.amountClp)}</p>}
        </article>

        <article className="panel">
          <h2>4. Auction</h2>
          <button className="button button-secondary full" disabled={busy || !me} onClick={loadAuctions}>Cargar Auctions ONLINE abiertas</button>
          <div className="compact-list">
            {auctions.map((item) => (
              <button key={item.id} className="list-card" onClick={() => selectAuction(item)}>
                <strong>{money.format(item.currentAmountClp)}</strong>
                <span>{item.durationMinutes} min · siguiente {money.format(item.nextActionableAmountClp)}</span>
              </button>
            ))}
          </div>
          {auction && <>
            <label className="field">Bid CLP<input value={bidAmount} inputMode="numeric" onChange={(e) => setBidAmount(e.target.value.replace(/\D/g, ''))} /></label>
            <div className="action-grid">
              <button className="button button-primary" disabled={busy} onClick={bid}>Pujar</button>
              <button className="button button-secondary" disabled={busy || auction.closeNowAmountClp == null} onClick={closeNow}>Ganar Ahora{auction.closeNowAmountClp ? ` · ${money.format(auction.closeNowAmountClp)}` : ''}</button>
            </div>
          </>}
        </article>
      </section>

      <section className="panel">
        <h2>5. Winner y Session ONLINE</h2>
        <div className="action-grid">
          <button className="button button-primary" disabled={busy || !appointmentId} onClick={confirmWinner}>Confirmar Winner</button>
          <button className="button button-secondary" disabled={busy || !sessionId} onClick={refreshSession}>Actualizar Session</button>
        </div>
        {session && <>
          <div className="data-strip">
            <div><small>Estado</small><strong>{session.status}</strong></div>
            <div><small>Facturable</small><strong>{session.billableSeconds} s</strong></div>
            <div><small>Acuerdo</small><strong>{money.format(session.agreedAmountClp)}</strong></div>
            <div><small>Grabación</small><strong>{session.persistentRecordingEnabled ? 'revisar' : 'no persistente'}</strong></div>
          </div>
          <OnlineVideoPanel sessionId={session.id} sessionStatus={session.status} onSessionChanged={setSession} onReconnectChanged={setReconnect} />
          <div className="action-grid">
            <button className="button button-primary" disabled={busy} onClick={acceptPaid}>Aceptar periodo pagado</button>
            <button className="button button-secondary" disabled={busy} onClick={refreshReconnect}>Ver reconnect</button>
            <button className="button button-secondary" disabled={busy} onClick={acceptResume}>Aceptar reanudación</button>
            <button className="button button-danger" disabled={busy} onClick={finishSession}>Finalizar</button>
          </div>
          {reconnect && <pre className="code-block">{JSON.stringify(reconnect, null, 2)}</pre>}
          {finish?.proportionalSettlementNeedsRoundingPolicy && <div className="error-banner">PENDING_ROUNDING_POLICY: no se inventa redondeo CLP.</div>}
        </>}
      </section>

      <section className="two-column">
        <article className="panel">
          <h2>6. Wallet</h2>
          <button className="button button-primary full" disabled={busy || !me} onClick={loadBalance}>Actualizar Wallet</button>
          {balance && <div className="data-strip"><div><small>Pending</small><strong>{money.format(balance.pendingClp)}</strong></div><div><small>Available</small><strong>{money.format(balance.availableClp)}</strong></div><div><small>Review</small><strong>{money.format(balance.heldForReviewClp)}</strong></div></div>}
        </article>

        <article className="panel">
          <h2>7. Safety</h2>
          <p className="hint"><strong>Reportar y bloquear son acciones distintas.</strong> Un reporte no prueba culpabilidad ni crea un hold financiero por sí solo.</p>
          <label className="field">Descripción del reporte<textarea value={reportDescription} onChange={(e) => setReportDescription(e.target.value)} placeholder="Describe el incidente" /></label>
          <div className="action-grid">
            <button className="button button-danger" disabled={busy || !host} onClick={createReport}>Reportar</button>
            <button className="button button-secondary" disabled={busy || !host} onClick={toggleBlock}>{blocked ? 'Desbloquear' : 'Bloquear'}</button>
          </div>
          {report && <p className="success-card">Reporte creado · {report.status}</p>}
        </article>
      </section>
    </main>
  );
}
