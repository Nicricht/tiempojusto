import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_API_TIMEOUT_MS,
  TiempoJustoNetworkError,
  tiempoJustoApi,
} from '../src/lib/api';

function setOnline(value: boolean): void {
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    get: () => value,
  });
}

describe('TiempoJusto API network states', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setOnline(true);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('fails fast as offline without sending a request', async () => {
    setOnline(false);
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(tiempoJustoApi.me()).rejects.toMatchObject({
      name: 'TiempoJustoNetworkError',
      kind: 'offline',
    } satisfies Partial<TiempoJustoNetworkError>);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('classifies an API request that exceeds the client deadline as timeout', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    vi.stubGlobal('fetch', fetchMock);

    const rejection = expect(tiempoJustoApi.me()).rejects.toMatchObject({
      name: 'TiempoJustoNetworkError',
      kind: 'timeout',
    } satisfies Partial<TiempoJustoNetworkError>);

    await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS + 1);
    await rejection;
  });
});
