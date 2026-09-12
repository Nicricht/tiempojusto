import { useState } from 'react';
import { tiempoJustoApi } from '../lib/api';
import { apiErrorText } from '../lib/errors';
import type { ProposalView } from '../lib/types';

const money = new Intl.NumberFormat('es-CL', {
  style: 'currency',
  currency: 'CLP',
  maximumFractionDigits: 0,
});

export default function ProposalPage() {
  const [profileId, setProfileId] = useState(() => window.localStorage.getItem('tj.hostProfileId') ?? '');
  const [proposalId, setProposalId] = useState(() => window.localStorage.getItem('tj.proposalId') ?? '');
  const [amount, setAmount] = useState('60000');
  const [duration, setDuration] = useState(30);
  const [proposal, setProposal] = useState<ProposalView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function create(): Promise<void> {
    const cleanProfileId = profileId.trim();
    const amountClp = Number(amount);
    if (!cleanProfileId) {
      setError('Host profile UUID es obligatorio.');
      return;
    }
    if (!Number.isInteger(amountClp) || amountClp <= 0) {
      setError('Monto CLP debe ser un entero positivo.');
      return;
    }

    setBusy(true);
    setError('');
    try {
      const result = await tiempoJustoApi.createProposal(cleanProfileId, amountClp, duration);
      setProposal(result);
      setProposalId(result.id);
      window.localStorage.setItem('tj.hostProfileId', cleanProfileId);
      window.localStorage.setItem('tj.proposalId', result.id);
    } catch (cause) {
      setError(apiErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function load(): Promise<void> {
    const cleanId = proposalId.trim();
    if (!cleanId) return;
    setBusy(true);
    setError('');
    try {
      const result = await tiempoJustoApi.getProposal(cleanId);
      setProposal(result);
      window.localStorage.setItem('tj.proposalId', cleanId);
    } catch (cause) {
      setError(apiErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <section className="page-heading">
        <div>
          <span className="status">Proposal</span>
          <h1>Expresa disposición, no reserves una cita</h1>
          <p>Proposal ONLINE expresa cuánto pagarías si después existe una Auction compatible. Crear Proposal no cobra ni adjudica.</p>
        </div>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      <section className="two-column">
        <article className="panel">
          <h2>Nueva Proposal ONLINE</h2>
          <label className="field">
            Host profile UUID
            <input value={profileId} onChange={(event) => setProfileId(event.target.value)} placeholder="UUID del perfil HOST" />
          </label>
          <label className="field">
            Monto CLP
            <input inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value.replace(/\D/g, ''))} />
          </label>
          <label className="field">
            Duración ONLINE
            <select value={duration} onChange={(event) => setDuration(Number(event.target.value))}>
              <option value={15}>15 minutos</option>
              <option value={30}>30 minutos</option>
              <option value={60}>60 minutos</option>
            </select>
          </label>
          <button className="button button-primary full" disabled={busy} onClick={create}>
            Crear Proposal {amount ? money.format(Number(amount)) : ''}
          </button>
          <p className="hint">Las validaciones finales de monto, elegibilidad, KYC, rol y compatibilidad pertenecen al backend.</p>
        </article>

        <article className="panel">
          <h2>Consultar Proposal</h2>
          <label className="field">
            Proposal UUID
            <input value={proposalId} onChange={(event) => setProposalId(event.target.value)} placeholder="UUID" />
          </label>
          <button className="button button-secondary full" disabled={busy || !proposalId.trim()} onClick={load}>Cargar Proposal</button>
          {proposal ? (
            <div className="proposal-summary">
              <span className={`status ${proposal.status === 'ACTIVE' ? 'status-ok' : 'status-warn'}`}>{proposal.status}</span>
              <strong>{money.format(proposal.amountClp)}</strong>
              <span>{proposal.modality} · {proposal.durationMinutes} min</span>
              <small>Válida hasta {new Date(proposal.validUntil).toLocaleString('es-CL')}</small>
            </div>
          ) : (
            <p className="hint">Carga una Proposal existente o crea una nueva para continuar el flujo.</p>
          )}
        </article>
      </section>

      {proposal && (
        <section className="data-strip">
          <div><small>Estado</small><strong>{proposal.status}</strong></div>
          <div><small>Modalidad</small><strong>{proposal.modality}</strong></div>
          <div><small>Duración</small><strong>{proposal.durationMinutes} min</strong></div>
          <div><small>Lock</small><strong>v{proposal.lockVersion}</strong></div>
        </section>
      )}

      <section className="guardrail">
        <div>
          <strong>Proposal ≠ Auction ≠ Appointment</strong>
          <p>Esta pantalla no convierte una intención económica en una reserva, una adjudicación ni un cobro.</p>
        </div>
        <span className="status">sin cobro al crear</span>
      </section>
    </main>
  );
}
