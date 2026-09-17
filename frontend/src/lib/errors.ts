import { TiempoJustoApiError, TiempoJustoNetworkError } from './api';

export function apiErrorText(error: unknown): string {
  if (error instanceof TiempoJustoApiError) {
    return error.problem?.code ? `${error.problem.code}: ${error.message}` : error.message;
  }
  if (error instanceof TiempoJustoNetworkError) {
    if (error.kind === 'offline') return 'Sin conexión. Revisa tu red e inténtalo nuevamente.';
    if (error.kind === 'timeout') return 'La solicitud tardó demasiado. Inténtalo nuevamente.';
    return 'No se pudo contactar al servicio. Inténtalo nuevamente.';
  }
  return error instanceof Error ? error.message : 'Ocurrió un error inesperado.';
}
