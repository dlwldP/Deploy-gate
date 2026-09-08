import { useState, type FormEvent } from 'react'
import { verifyToken } from '../api'
import { UnauthorizedError } from '../auth'

export function LoginScreen({
  notice,
  onAuthenticated,
}: {
  notice: string | null
  onAuthenticated: (token: string) => void
}) {
  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = token.trim()
    if (!trimmed || busy) return

    setBusy(true)
    setError(null)
    try {
      await verifyToken(trimmed)
      onAuthenticated(trimmed)
    } catch (caught: unknown) {
      setError(
        caught instanceof UnauthorizedError
          ? caught.message
          : caught instanceof Error
            ? caught.message
            : String(caught),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="login__card" onSubmit={handleSubmit}>
        <div className="login__brand">
          <span className="login__mark" aria-hidden="true" />
          <div>
            <h1>deploy-gate</h1>
            <p className="login__tagline">배포 승인 게이트 · 관리 콘솔</p>
          </div>
        </div>

        {notice && <p className="alert alert--warn">{notice}</p>}

        <label className="field">
          <span className="field__label">API 토큰</span>
          <input
            className="field__input"
            type="password"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            placeholder="dgt_..."
            autoFocus
            autoComplete="off"
            spellCheck={false}
          />
        </label>

        {error && <p className="alert alert--error">{error}</p>}

        <button className="button" type="submit" disabled={busy || token.trim().length === 0}>
          {busy ? '확인 중…' : '접속'}
        </button>

        <p className="login__hint">
          <code>admin:read</code> claim을 가진 deployer 토큰이 필요합니다. 토큰은 이 탭에만 저장되며
          탭을 닫으면 삭제됩니다.
        </p>
      </form>
    </div>
  )
}
