import { useState } from 'react';
import { tiempoJustoApi, TiempoJustoApiError } from '../lib/api';
import { apiErrorText } from '../lib/errors';
import type { AuthMe, KycStartResult, KycStatusResult } from '../lib/types';

export default function IdentityPage() {
  const [me, setMe] = useState<AuthMe | null>(null);
  const [kyc, setKyc] = useState<KycStatusResult | null>(null);
  const [start, setStart] = useState<KycStartResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [noKyc, setNoKyc] = useState(false);

  async function loadAccount(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      setMe(await tiempoJustoApi.me());
    } catch (cause) {
      setError(apiErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function loadKyc(): Promise<void> {
    setBusy(true);
    setError('');
    setNoKyc(false);
    try {
      setKyc(await tiempoJustoApi.latestKyc());
    } catch (cause) {
      if (cause instanceof TiempoJustoApiError && cause.status === 404) {
        setKyc(null);
        setNoKyc(true);
      } else {
        setError(apiErrorText(cause));
      }
    } finally {
      setBusy(false);
    }
  }

  async function startKyc(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const result = await tiempoJustoApi.startKyc();
      setStart(result);
      setNoKyc(false);
    } catch (cause) {
      setError(apiErrorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function openVerification(): void {
    if (!start?.verificationUrl) return;
    window.open(start.verificationUrl, '_blank', 'noopener,noreferrer');
  }

  return (
    <main className="page">
      <section className="page-heading row-heading">
        <div>
          <span className="status status-ok">Identidad</span>
          <h1>Cuenta y KYC</h1>
          <p>La interfaz consume el estado real del backend. El proveedor de KYC productivo sigue siendo un gate separado.</p>
        </div>
        <div className="hero-actions compact-actions">
          <button className="button button-secondary" disabled={busy} onClick={loadAccount}>Consultar cuenta</button>
          <button className="button button-secondary" disabled={busy} onClick={loadKyc}>Consultar KYC</button>
        </div>
      </section>

      {error && <div className="error-banner" role="alert">{error}</div>}

      <section className="two-column">
        <article className="panel">
          <span className="status">Cuenta</span>
          <h2>{me ? me.publicId : 'Actor actual'}</h2>
          {me ? (
            <div className="identity-list">
              <div><small>Rol</small><strong>{me.role}</strong></div>
              <div><small>Estado</small><strong>{me.accountStatus}</strong></div>
              <div><small>User ID</small><code>{me.userId}</code></div>
            </div>
          ) : (
            <p className="hint">Consulta <code>/api/v1/auth/me</code> para validar qué identidad reconoce el backend.</p>
          )}
        </article>

        <article className="panel">
          <span className={`status ${kyc?.status === 'VERIFIED' ? 'status-ok' : 'status-warn'}`}>KYC</span>
          <h2>{kyc?.status ?? (noKyc ? 'Sin verificación' : 'Estado no consultado')}</h2>
          {kyc ? (
            <div className="identity-list">
              <div><small>Adulto verificado</small><strong>{kyc.verifiedAdult ? 'Sí' : 'No'}</strong></div>
              <div><small>Proveedor</small><strong>{kyc.provider}</strong></div>
              <div><small>País legal</small><strong>{kyc.legalCountryCode ?? 'No informado'}</strong></div>
              <div><small>Verification ID</small><code>{kyc.verificationId}</code></div>
            </div>
          ) : (
            <p className="hint">TiempoJusto persiste el resultado mínimo necesario, no documentos ni biometría cruda del proveedor.</p>
          )}
          <button className="button button-primary full kyc-action" disabled={busy} onClick={startKyc}>
            Iniciar verificación
          </button>
        </article>
      </section>

      {start && (
        <section className="success-card">
          <span className="status status-ok">Verificación creada</span>
          <h2>{start.provider} · {start.status}</h2>
          <code>{start.verificationId}</code>
          <p>El enlace proviene del adapter KYC configurado en backend. Esta UI no genera ni almacena documentos de identidad.</p>
          {start.verificationUrl && (
            <button className="button button-dark" onClick={openVerification}>Abrir verificación</button>
          )}
        </section>
      )}

      <section className="guardrail">
        <div>
          <strong>18+ lo determina KYC</strong>
          <p>La UI no estima edad, sexo ni identidad mediante heurísticas. Solo representa el resultado del proveedor y las reglas del backend.</p>
        </div>
        <span className="status">provider-neutral</span>
      </section>
    </main>
  );
}
