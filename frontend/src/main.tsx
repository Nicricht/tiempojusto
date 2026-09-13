import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import Root from './Root';
import { oidc } from './lib/oidc';
import './styles.css';
import './styles-extra.css';

async function bootstrap(): Promise<void> {
  const params = new URLSearchParams(window.location.search);
  if (params.has('code') || params.has('error')) {
    try {
      await oidc.completeCallback();
      window.location.hash = window.location.hash || '#/flow';
    } catch (cause) {
      sessionStorage.setItem('tj.oidc.error', cause instanceof Error ? cause.message : String(cause));
      window.history.replaceState({}, document.title, `${window.location.pathname}#/flow`);
    }
  }

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <Root />
    </StrictMode>,
  );
}

void bootstrap();
