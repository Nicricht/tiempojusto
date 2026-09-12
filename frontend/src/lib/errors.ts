import { TiempoJustoApiError } from './api';

export function apiErrorText(error: unknown): string {
  if (error instanceof TiempoJustoApiError) {
    return error.problem?.code ? `${error.problem.code}: ${error.message}` : error.message;
  }
  return error instanceof Error ? error.message : 'Ocurrió un error inesperado.';
}
