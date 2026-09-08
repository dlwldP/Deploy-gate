const STORAGE_KEY = 'deploygate.token'

/**
 * The API token lives in sessionStorage, not localStorage: it disappears when the
 * tab closes, which limits the window in which a shared machine exposes it.
 */
export const tokenStore = {
  get(): string | null {
    return sessionStorage.getItem(STORAGE_KEY)
  },
  set(token: string): void {
    sessionStorage.setItem(STORAGE_KEY, token)
  },
  clear(): void {
    sessionStorage.removeItem(STORAGE_KEY)
  },
}

/** Thrown when the server rejects the token, so the UI can drop back to the login screen. */
export class UnauthorizedError extends Error {
  constructor(message = '토큰이 유효하지 않거나 권한이 없습니다.') {
    super(message)
    this.name = 'UnauthorizedError'
  }
}
