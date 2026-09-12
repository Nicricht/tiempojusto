import { useEffect, useMemo, useState } from 'react';
import { getDevActorId, setDevActorId, tiempoJustoApi } from '../lib/api';
import { apiErrorText } from '../lib/errors';
import type { AdminAuctionBundle, AdminRow, AdminSessionBundle, AuthMe } from '../lib/types';

type Tab = 'review' | 'users' | 'runtime' | 'finance' | 'audit';

function valueText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function rowText(row: AdminRow, key: string): string {
  return valueText(row[key]);
}

function RowTable({ rows, empty = 'Sin resultados.' }: { rows: AdminRow[]; empty?: string }) {
  const columns = useMemo(() => {
    const names = new Set<string>();
    rows.forEach((row) => Object.keys(row).forEach((key) => names.add(key)));
    return Array.from(names);
  }, [rows]);

  if (!rows.length) return <p className="hint">{empty}</p>;

  return (
    <div className="admin-table-wrap">
      <table className="admin-table">
        <thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${rowText(row, 'id')}-${rowText(row, 'task_id')}-${index}`}>
              {columns.map((column) => <td key={column}>{valueText(row[column])}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function JsonCard({ title, value }: { title: string; value: AdminRow | null }) {
  return (
    <article className="panel admin-json-card">
      <span className="status">{title}</span>
      {value ? <pre>{JSON.stringify(value, null, 2)}</pre> : <p className="hint">Sin datos cargados.</p>}
    </article>
  );
}

export default function AdminPage() {
  const [tab, setTab] = useState<Tab>('review');
  const [me, setMe] = useState<AuthMe | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [devActor, setDevActor] = useState(() => getDevActorId());

  const [queue, setQueue] = useState<AdminRow[]>([]);
  const [appeals, setAppeals] = useState<AdminRow[]>([]);
  const [caseId, setCaseId] = useState('');
  const [caseDetail, setCaseDetail] = useState<AdminRow | null>(null);
  const [evidence, setEvidence] = useState<AdminRow[]>([]);
  const [decisionOutcome, setDecisionOutcome] = useState('CONFIRMED');
  const [decisionSeverity, setDecisionSeverity] = useState('S1');
  const [decisionReason, setDecisionReason] = useState('');
  const [appealId, setAppealId] = useState('');
  const [appealOutcome, setAppealOutcome] = useState('MAINTAIN');
  const [appealReason, setAppealReason] = useState('');

  const [userQuery, setUserQuery] = useState('');
  const [users, setUsers] = useState<AdminRow[]>([]);

  const [auctionId, setAuctionId] = useState('');
  const [auctionBundle, setAuctionBundle] = useState<AdminAuctionBundle | null>(null);
  const [sessionId, setSessionId] = useState('');
  const [sessionBundle, setSessionBundle] = useState<AdminSessionBundle | null>(null);

  const [payoutId, setPayoutId] = useState('');
  const [payout, setPayout] = useState<AdminRow | null>(null);
  const [holds, setHolds] = useState<AdminRow[]>([]);
  const [holdReasonCode, setHoldReasonCode] = useState('OBJECTIVE_INCIDENT');
  const [holdEvidenceRef, setHoldEvidenceRef] = useState('');
  const [holdCaseId, setHoldCaseId] = useState('');
  const [ledgerId, setLedgerId] = useState('');
  const [ledger, setLedger] = useState<AdminRow[]>([]);

  const [auditCaseId, setAuditCaseId] = useState('');
  const [audit, setAudit] = useState<AdminRow[]>([]);
  const [actionTargetId, setActionTargetId] = useState('');
  const [actions, setActions] = useState<AdminRow[]>([]);
  const [riskUserId, setRiskUserId] = useState('');
  const [riskSignals, setRiskSignals] = useState<AdminRow[]>([]);

  const isAdmin = me?.role === 'ADMIN' && me.accountStatus === 'ACTIVE';

  useEffect(() => {
    void loadMe();
  }, []);

  async function run(work: () => Promise<void>, success?: string): Promise<void> {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      if (success) setNotice(success);
    } catch (cause) {
      setError(apiErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function loadMe(): Promise<void> {
    await run(async () => setMe(await tiempoJustoApi.me()));
  }

  function applyDevActor(): void {
    setDevActorId(devActor);
    void loadMe();
  }

  async function loadReview(): Promise<void> {
    await run(async () => {
      const [nextQueue, nextAppeals] = await Promise.all([
        tiempoJustoApi.adminReviewQueue(),
        tiempoJustoApi.adminAppeals(),
      ]);
      setQueue(nextQueue);
      setAppeals(nextAppeals);
    });
  }

  async function claim(taskId: string): Promise<void> {
    await run(async () => {
      await tiempoJustoApi.adminClaimReview(taskId);
      setQueue(await tiempoJustoApi.adminReviewQueue());
    }, 'Tarea tomada y acción administrativa auditada.');
  }

  async function loadCase(selectedCaseId = caseId): Promise<void> {
    const clean = selectedCaseId.trim();
    if (!clean) return;
    await run(async () => {
      const [detail, timeline] = await Promise.all([
        tiempoJustoApi.adminSafetyCase(clean),
        tiempoJustoApi.adminEvidenceTimeline(clean),
      ]);
      setCaseDetail(detail);
      setEvidence(timeline);
      const severity = detail.severity;
      if (typeof severity === 'string') setDecisionSeverity(severity);
    });
  }

  function openCase(selectedCaseId: string): void {
    setCaseId(selectedCaseId);
    void loadCase(selectedCaseId);
  }

  async function decideCase(): Promise<void> {
    const clean = caseId.trim();
    if (!clean || !decisionReason.trim()) return;
    if (!window.confirm(`Confirmar decisión ${decisionOutcome} con severidad ${decisionSeverity}. Esta acción queda auditada.`)) return;
    await run(async () => {
      setCaseDetail(await tiempoJustoApi.adminDecideCase(clean, decisionOutcome, decisionSeverity, decisionReason.trim()));
      setEvidence(await tiempoJustoApi.adminEvidenceTimeline(clean));
      setQueue(await tiempoJustoApi.adminReviewQueue());
    }, 'Decisión Safety registrada y auditada.');
  }

  async function resolveAppeal(): Promise<void> {
    if (!appealId.trim() || !appealReason.trim()) return;
    if (!window.confirm(`Resolver appeal como ${appealOutcome}. Esta acción queda auditada.`)) return;
    await run(async () => {
      await tiempoJustoApi.adminResolveAppeal(appealId.trim(), appealOutcome, appealReason.trim());
      setAppeals(await tiempoJustoApi.adminAppeals());
    }, 'Appeal resuelta y auditada.');
  }

  async function searchUsers(): Promise<void> {
    await run(async () => setUsers(await tiempoJustoApi.adminUsers(userQuery.trim())));
  }

  async function loadAuction(): Promise<void> {
    if (!auctionId.trim()) return;
    await run(async () => setAuctionBundle(await tiempoJustoApi.adminAuction(auctionId.trim())));
  }

  async function loadSession(): Promise<void> {
    if (!sessionId.trim()) return;
    await run(async () => setSessionBundle(await tiempoJustoApi.adminSession(sessionId.trim())));
  }

  async function loadPayout(): Promise<void> {
    if (!payoutId.trim()) return;
    await run(async () => {
      const [nextPayout, nextHolds] = await Promise.all([
        tiempoJustoApi.adminPayout(payoutId.trim()),
        tiempoJustoApi.adminPayoutHolds(payoutId.trim()),
      ]);
      setPayout(nextPayout);
      setHolds(nextHolds);
    });
  }

  async function createHold(): Promise<void> {
    if (!payoutId.trim() || !holdReasonCode.trim() || !holdEvidenceRef.trim()) return;
    if (!window.confirm('Crear un payout hold objetivo. No modifica montos ni el ledger y quedará auditado.')) return;
    await run(async () => {
      await tiempoJustoApi.adminCreatePayoutHold(
        payoutId.trim(),
        holdReasonCode.trim(),
        holdEvidenceRef.trim(),
        holdCaseId.trim() || undefined,
      );
      setPayout(await tiempoJustoApi.adminPayout(payoutId.trim()));
      setHolds(await tiempoJustoApi.adminPayoutHolds(payoutId.trim()));
    }, 'Payout hold creado mediante flujo controlado y auditado.');
  }

  async function releaseHold(holdId: string): Promise<void> {
    const reason = window.prompt('Motivo obligatorio para liberar este hold');
    if (!reason?.trim()) return;
    if (!window.confirm('Confirmar liberación del payout hold. La acción quedará auditada.')) return;
    await run(async () => {
      await tiempoJustoApi.adminReleasePayoutHold(payoutId.trim(), holdId, reason.trim());
      setPayout(await tiempoJustoApi.adminPayout(payoutId.trim()));
      setHolds(await tiempoJustoApi.adminPayoutHolds(payoutId.trim()));
    }, 'Payout hold liberado y auditado.');
  }

  async function loadLedger(): Promise<void> {
    if (!ledgerId.trim()) return;
    await run(async () => setLedger(await tiempoJustoApi.adminLedger(ledgerId.trim())));
  }

  async function loadAudit(): Promise<void> {
    await run(async () => setAudit(await tiempoJustoApi.adminAuditLog(auditCaseId.trim())));
  }

  async function loadActions(): Promise<void> {
    await run(async () => setActions(await tiempoJustoApi.adminActions(actionTargetId.trim())));
  }

  async function loadRisk(): Promise<void> {
    await run(async () => setRiskSignals(await tiempoJustoApi.adminRiskSignals(riskUserId.trim())));
  }

  return (
    <main className="page admin-page">
      <section className="page-heading row-heading">
        <div>
          <span className={`status ${isAdmin ? 'status-ok' : 'status-warn'}`}>Admin</span>
          <h1>Operación y Safety</h1>
          <p>Consola mínima para piloto. El backend conserva la autoridad, aplica RBAC y audita toda mutación administrativa.</p>
        </div>
        <div className="admin-identity">
          <strong>{me?.publicId ?? 'Actor no validado'}</strong>
          <small>{me ? `${me.role} · ${me.accountStatus}` : 'Consulta /auth/me'}</small>
          <button className="button button-secondary" disabled={busy} onClick={() => void loadMe()}>Revalidar acceso</button>
        </div>
      </section>

      {tiempoJustoApi.devMode && (
        <section className="guardrail admin-dev-actor">
          <div>
            <strong>Actor de desarrollo</strong>
            <p>Solo existe en builds con VITE_TJ_DEV_MODE habilitado.</p>
          </div>
          <div className="admin-inline-form">
            <input value={devActor} onChange={(event) => setDevActor(event.target.value)} placeholder="UUID ADMIN" />
            <button className="button button-dark" onClick={applyDevActor}>Aplicar</button>
          </div>
        </section>
      )}

      {error && <div className="error-banner" role="alert">{error}</div>}
      {notice && <div className="success-card admin-notice" role="status">{notice}</div>}

      {!isAdmin ? (
        <section className="panel admin-access-denied">
          <span className="status status-warn">RBAC</span>
          <h2>Se requiere un ADMIN activo</h2>
          <p>La UI no intenta saltarse el control. El servidor vuelve a validar el rol en cada endpoint administrativo.</p>
        </section>
      ) : (
        <>
          <nav className="admin-tabs" aria-label="Secciones administrativas">
            <button className={tab === 'review' ? 'active' : ''} onClick={() => setTab('review')}>Safety</button>
            <button className={tab === 'users' ? 'active' : ''} onClick={() => setTab('users')}>Usuarios/KYC</button>
            <button className={tab === 'runtime' ? 'active' : ''} onClick={() => setTab('runtime')}>Auction/Session</button>
            <button className={tab === 'finance' ? 'active' : ''} onClick={() => setTab('finance')}>Finanzas</button>
            <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>Auditoría</button>
          </nav>

          {tab === 'review' && (
            <section className="admin-stack">
              <article className="panel">
                <div className="admin-section-title">
                  <div><span className="status">HumanReviewQueue</span><h2>Cola de revisión</h2></div>
                  <button className="button button-secondary" disabled={busy} onClick={() => void loadReview()}>Actualizar</button>
                </div>
                <div className="admin-card-grid">
                  {queue.map((task) => (
                    <div className="admin-task" key={rowText(task, 'task_id')}>
                      <strong>{rowText(task, 'severity')} · {rowText(task, 'task_type')}</strong>
                      <code>{rowText(task, 'safety_case_id')}</code>
                      <small>{rowText(task, 'task_status')}</small>
                      <div className="admin-inline-actions">
                        {rowText(task, 'task_status') === 'QUEUED' && (
                          <button className="button button-dark" disabled={busy} onClick={() => void claim(rowText(task, 'task_id'))}>Tomar</button>
                        )}
                        <button className="button button-secondary" onClick={() => openCase(rowText(task, 'safety_case_id'))}>Abrir caso</button>
                      </div>
                    </div>
                  ))}
                  {!queue.length && <p className="hint">Carga la cola para revisar casos pendientes.</p>}
                </div>
              </article>

              <article className="panel">
                <span className="status">SafetyCase</span>
                <h2>Detalle, evidencia y decisión</h2>
                <div className="admin-inline-form">
                  <input value={caseId} onChange={(event) => setCaseId(event.target.value)} placeholder="Safety case UUID" />
                  <button className="button button-secondary" disabled={busy} onClick={() => void loadCase()}>Consultar</button>
                </div>
                {caseDetail && <pre className="admin-pre">{JSON.stringify(caseDetail, null, 2)}</pre>}
                <RowTable rows={evidence} empty="Sin timeline cargado." />
                <div className="admin-form-grid">
                  <label className="field"><span>Outcome</span><select value={decisionOutcome} onChange={(event) => setDecisionOutcome(event.target.value)}><option>CONFIRMED</option><option>UNDETERMINED</option><option>DISMISSED</option></select></label>
                  <label className="field"><span>Severidad final</span><select value={decisionSeverity} onChange={(event) => setDecisionSeverity(event.target.value)}>{['S0','S1','S2','S3','S4','S5'].map((level) => <option key={level}>{level}</option>)}</select></label>
                  <label className="field admin-wide"><span>Motivo obligatorio</span><input value={decisionReason} onChange={(event) => setDecisionReason(event.target.value)} placeholder="Fundamento de la decisión" /></label>
                </div>
                <button className="button button-primary" disabled={busy || !caseId.trim() || !decisionReason.trim()} onClick={() => void decideCase()}>Registrar decisión</button>
              </article>

              <article className="panel">
                <span className="status">Appeals</span>
                <h2>Apelaciones</h2>
                <RowTable rows={appeals} empty="Carga Safety para obtener apelaciones." />
                <div className="admin-form-grid">
                  <label className="field"><span>Appeal UUID</span><input value={appealId} onChange={(event) => setAppealId(event.target.value)} /></label>
                  <label className="field"><span>Resultado</span><select value={appealOutcome} onChange={(event) => setAppealOutcome(event.target.value)}><option>MAINTAIN</option><option>REDUCE</option><option>REVOKE</option></select></label>
                  <label className="field admin-wide"><span>Motivo obligatorio</span><input value={appealReason} onChange={(event) => setAppealReason(event.target.value)} /></label>
                </div>
                <button className="button button-primary" disabled={busy || !appealId.trim() || !appealReason.trim()} onClick={() => void resolveAppeal()}>Resolver appeal</button>
              </article>
            </section>
          )}

          {tab === 'users' && (
            <section className="admin-stack">
              <article className="panel">
                <span className="status">Usuarios + KYC</span>
                <h2>Búsqueda operativa</h2>
                <p className="hint">Busca por UUID, public ID, username, display name o email. La respuesta no expone documentos KYC, biometría, fecha de nacimiento ni referencias privadas del proveedor.</p>
                <div className="admin-inline-form">
                  <input value={userQuery} onChange={(event) => setUserQuery(event.target.value)} placeholder="Usuario, UUID o email" />
                  <button className="button button-dark" disabled={busy} onClick={() => void searchUsers()}>Buscar</button>
                </div>
                <RowTable rows={users} />
              </article>
            </section>
          )}

          {tab === 'runtime' && (
            <section className="admin-stack">
              <article className="panel">
                <span className="status">Auction + Bids</span>
                <h2>Auditoría de subasta</h2>
                <div className="admin-inline-form">
                  <input value={auctionId} onChange={(event) => setAuctionId(event.target.value)} placeholder="Auction UUID" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadAuction()}>Consultar</button>
                </div>
                <JsonCard title="Auction" value={auctionBundle?.auction ?? null} />
                <RowTable rows={auctionBundle?.bids ?? []} empty="Sin bids cargados." />
              </article>

              <article className="panel">
                <span className="status">Appointment / Session / Reconnect</span>
                <h2>Timeline técnico</h2>
                <div className="admin-inline-form">
                  <input value={sessionId} onChange={(event) => setSessionId(event.target.value)} placeholder="Session UUID" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadSession()}>Consultar</button>
                </div>
                <JsonCard title="Session" value={sessionBundle?.session ?? null} />
                <h3>Segmentos</h3><RowTable rows={sessionBundle?.segments ?? []} />
                <h3>Participantes técnicos</h3><RowTable rows={sessionBundle?.participants ?? []} />
                <h3>Incidentes/reconnect</h3><RowTable rows={sessionBundle?.incidents ?? []} />
              </article>
            </section>
          )}

          {tab === 'finance' && (
            <section className="admin-stack">
              <article className="panel">
                <span className="status">Payout</span>
                <h2>Lectura y objective holds</h2>
                <p className="hint">Montos, estado y ledger son read-only. La única mutación financiera de Admin disponible aquí es crear o liberar un hold objetivo con evidencia y auditoría.</p>
                <div className="admin-inline-form">
                  <input value={payoutId} onChange={(event) => setPayoutId(event.target.value)} placeholder="Payout UUID" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadPayout()}>Consultar</button>
                </div>
                <JsonCard title="Payout read-only" value={payout} />
                <RowTable rows={holds} empty="Sin holds cargados." />
                {holds.filter((hold) => rowText(hold, 'status') === 'ACTIVE').map((hold) => (
                  <button key={rowText(hold, 'id')} className="button button-secondary admin-release" disabled={busy} onClick={() => void releaseHold(rowText(hold, 'id'))}>
                    Liberar hold {rowText(hold, 'id')}
                  </button>
                ))}
                <div className="admin-form-grid admin-hold-form">
                  <label className="field"><span>Reason code</span><input value={holdReasonCode} onChange={(event) => setHoldReasonCode(event.target.value)} /></label>
                  <label className="field"><span>Evidence ref</span><input value={holdEvidenceRef} onChange={(event) => setHoldEvidenceRef(event.target.value)} placeholder="Referencia objetiva, no archivo crudo" /></label>
                  <label className="field admin-wide"><span>Safety case UUID opcional</span><input value={holdCaseId} onChange={(event) => setHoldCaseId(event.target.value)} /></label>
                </div>
                <button className="button button-primary" disabled={busy || !payoutId.trim() || !holdEvidenceRef.trim()} onClick={() => void createHold()}>Crear objective hold</button>
              </article>

              <article className="panel">
                <span className="status">Ledger</span>
                <h2>Solo lectura</h2>
                <div className="admin-inline-form">
                  <input value={ledgerId} onChange={(event) => setLedgerId(event.target.value)} placeholder="Ledger transaction UUID" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadLedger()}>Consultar</button>
                </div>
                <RowTable rows={ledger} />
              </article>
            </section>
          )}

          {tab === 'audit' && (
            <section className="admin-stack">
              <article className="panel">
                <span className="status">AuditEvent</span>
                <h2>Eventos inmutables</h2>
                <div className="admin-inline-form">
                  <input value={auditCaseId} onChange={(event) => setAuditCaseId(event.target.value)} placeholder="Safety case UUID opcional" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadAudit()}>Cargar audit log</button>
                </div>
                <RowTable rows={audit} />
              </article>

              <article className="panel">
                <span className="status">AdminAction</span>
                <h2>Acciones administrativas</h2>
                <div className="admin-inline-form">
                  <input value={actionTargetId} onChange={(event) => setActionTargetId(event.target.value)} placeholder="Target UUID opcional" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadActions()}>Cargar acciones</button>
                </div>
                <RowTable rows={actions} />
              </article>

              <article className="panel">
                <span className="status">Risk signals</span>
                <h2>Señales privadas</h2>
                <div className="admin-inline-form">
                  <input value={riskUserId} onChange={(event) => setRiskUserId(event.target.value)} placeholder="User UUID opcional" />
                  <button className="button button-dark" disabled={busy} onClick={() => void loadRisk()}>Cargar señales</button>
                </div>
                <RowTable rows={riskSignals} />
              </article>
            </section>
          )}
        </>
      )}

      <section className="guardrail">
        <div>
          <strong>Privacidad por diseño</strong>
          <p>Esta consola no presenta documentos KYC, biometría cruda, audiovisual privado ni credenciales TURN. Finance permanece read-only salvo payout holds auditados.</p>
        </div>
        <span className="status">least-privilege</span>
      </section>
    </main>
  );
}
