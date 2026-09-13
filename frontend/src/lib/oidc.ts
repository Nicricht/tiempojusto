import { clearAccessToken, setAccessToken } from './api';

const AUTHORIZATION_ENDPOINT = (import.meta.env.VITE_TJ_OIDC_AUTHORIZATION_ENDPOINT as string | undefined)?.trim() ?? '';
const TOKEN_ENDPOINT = (import.meta.env.VITE_TJ_OIDC_TOKEN_ENDPOINT as string | undefined)?.trim() ?? '';
const CLIENT_ID = (import.meta.env.VITE_TJ_OIDC_CLIENT_ID as string | undefined)?.trim() ?? '';
const SCOPE = (import.meta.env.VITE_TJ_OIDC_SCOPE as string | undefined)?.trim() || 'openid profile';
const REDIRECT_URI = (import.meta.env.VITE_TJ_OIDC_REDIRECT_URI as string | undefined)?.trim()
  || `${window.location.origin}${window.location.pathname}`;

const STATE_KEY = 'tj.oidc.state';
const VERIFIER_KEY = 'tj.oidc.verifier';
const RETURN_HASH_KEY = 'tj.oidc.returnHash';

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomValue(bytes = 32): string {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return base64Url(data);
}

async function challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

export const oidc = {
  configured: Boolean(AUTHORIZATION_ENDPOINT && TOKEN_ENDPOINT && CLIENT_ID),

  async login(returnHash = '#/explore'): Promise<void> {
    if (!this.configured) throw new Error('OIDC no está configurado para este entorno.');
    const state = randomValue();
    const verifier = randomValue(48);
    sessionStorage.setItem(STATE_KEY, state);
    sessionStorage.setItem(VERIFIER_KEY, verifier);
    sessionStorage.setItem(RETURN_HASH_KEY, returnHash);

    const url = new URL(AUTHORIZATION_ENDPOINT);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('client_id', CLIENT_ID);
    url.searchParams.set('redirect_uri', REDIRECT_URI);
    url.searchParams.set('scope', SCOPE);
    url.searchParams.set('state', state);
    url.searchParams.set('code_challenge_method', 'S256');
    url.searchParams.set('code_challenge', await challenge(verifier));
    window.location.assign(url.toString());
  },

  async completeCallback(): Promise<string | null> {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const state = params.get('state');
    const error = params.get('error');
    if (error) throw new Error(params.get('error_description') || error);
    if (!code) return null;

    const expectedState = sessionStorage.getItem(STATE_KEY);
    const verifier = sessionStorage.getItem(VERIFIER_KEY);
    if (!expectedState || !verifier || state !== expectedState) {
      throw new Error('Respuesta OIDC inválida: state no coincide.');
    }

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: REDIRECT_URI,
      client_id: CLIENT_ID,
      code_verifier: verifier,
    });
    const response = await fetch(TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
      body,
    });
    if (!response.ok) throw new Error(`OIDC token exchange falló con HTTP ${response.status}.`);
    const payload = await response.json() as { access_token?: string };
    if (!payload.access_token) throw new Error('OIDC no devolvió access_token.');

    setAccessToken(payload.access_token);
    sessionStorage.removeItem(STATE_KEY);
    sessionStorage.removeItem(VERIFIER_KEY);
    const returnHash = sessionStorage.getItem(RETURN_HASH_KEY) || '#/explore';
    sessionStorage.removeItem(RETURN_HASH_KEY);
    window.history.replaceState({}, document.title, `${window.location.pathname}${returnHash}`);
    return returnHash;
  },

  logout(): void {
    clearAccessToken();
  },
};
