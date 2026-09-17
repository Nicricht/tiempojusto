import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  latestKyc: vi.fn(),
  discoverOnline: vi.fn(),
}));

vi.mock('../src/lib/api', async () => {
  const actual = await vi.importActual<typeof import('../src/lib/api')>('../src/lib/api');
  return {
    ...actual,
    clearAccessToken: vi.fn(),
    tiempoJustoApi: {
      ...actual.tiempoJustoApi,
      devMode: false,
      me: apiMocks.me,
      latestKyc: apiMocks.latestKyc,
      discoverOnline: apiMocks.discoverOnline,
    },
  };
});

vi.mock('../src/lib/oidc', () => ({
  oidc: {
    configured: true,
    login: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('../src/features/OnlineVideoPanel', () => ({
  OnlineVideoPanel: () => null,
}));

import GoldenPathPage from '../src/features/GoldenPathPage';

function setOnline(value: boolean): void {
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    get: () => value,
  });
}

const VERIFIED = {
  verificationId: 'kyc-1',
  provider: 'TEST',
  status: 'VERIFIED',
  verifiedAdult: true,
  legalCountryCode: 'CL',
  verifiedAt: '2026-09-16T12:00:00Z',
  expiresAt: null,
  createdAt: '2026-09-16T11:00:00Z',
};

const ME = {
  userId: 'user-1',
  publicId: 'TJ-USER-1',
  role: 'BIDDER',
  accountStatus: 'ACTIVE',
};

describe('GoldenPathPage operational states', () => {
  beforeEach(() => {
    setOnline(true);
    localStorage.clear();
    sessionStorage.clear();
    apiMocks.me.mockReset();
    apiMocks.latestKyc.mockReset();
    apiMocks.discoverOnline.mockReset();
  });

  afterEach(() => cleanup());

  it('shows a visible loading state while an authoritative request is pending', async () => {
    apiMocks.me.mockImplementation(() => new Promise(() => undefined));

    render(<GoldenPathPage />);

    expect(await screen.findByRole('status')).toHaveTextContent('Procesando solicitud');
  });

  it('shows an explicit offline state and recovers when the browser reconnects', async () => {
    apiMocks.me.mockResolvedValue(ME);
    apiMocks.latestKyc.mockResolvedValue(VERIFIED);

    render(<GoldenPathPage />);
    await screen.findByText('TJ-USER-1');

    setOnline(false);
    fireEvent(window, new Event('offline'));
    expect(screen.getByText(/Sin conexión/i)).toBeInTheDocument();

    setOnline(true);
    fireEvent(window, new Event('online'));
    await waitFor(() => expect(screen.queryByText(/Sin conexión/i)).not.toBeInTheDocument());
  });

  it('distinguishes an empty Discovery response from a list that has not been loaded yet', async () => {
    apiMocks.me.mockResolvedValue(ME);
    apiMocks.latestKyc.mockResolvedValue(VERIFIED);
    apiMocks.discoverOnline.mockResolvedValue({ items: [], nextCursor: null, hasMore: false });

    render(<GoldenPathPage />);
    await screen.findByText('TJ-USER-1');

    fireEvent.click(screen.getByRole('button', { name: 'Buscar HOSTS' }));

    expect(await screen.findByText('No hay HOSTS ONLINE disponibles.')).toBeInTheDocument();
  });
});
