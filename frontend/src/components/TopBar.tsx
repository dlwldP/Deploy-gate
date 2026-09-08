export function TopBar({ onReload, onSignOut, busy }: {
  onReload: () => void
  onSignOut: () => void
  busy: boolean
}) {
  return (
    <header className="topbar">
      <div className="topbar__inner">
        <div className="topbar__brand">
          <span className="topbar__mark" aria-hidden="true" />
          <span className="topbar__name">deploy-gate</span>
          <span className="topbar__divider" aria-hidden="true" />
          <span className="topbar__section">관리 콘솔</span>
        </div>

        <div className="topbar__actions">
          <button className="button button--ghost" onClick={onReload} disabled={busy}>
            {busy ? '새로고침 중…' : '새로고침'}
          </button>
          <button className="button button--ghost" onClick={onSignOut}>
            연결 해제
          </button>
        </div>
      </div>
    </header>
  )
}
