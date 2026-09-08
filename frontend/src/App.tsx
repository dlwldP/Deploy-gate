import { useCallback, useState } from 'react'
import { tokenStore } from './auth'
import { DeployerTable } from './components/DeployerTable'
import { HistoryTable } from './components/HistoryTable'
import { LoginScreen } from './components/LoginScreen'
import { StackPolicyTable } from './components/StackPolicyTable'
import { SummaryCards } from './components/SummaryCards'
import { TopBar } from './components/TopBar'
import { useDashboardData } from './useDashboardData'
import './App.css'

function Dashboard({ onSignOut }: { onSignOut: (notice: string | null) => void }) {
  const handleUnauthorized = useCallback(
    (message: string) => {
      tokenStore.clear()
      onSignOut(message)
    },
    [onSignOut],
  )

  const { state, reload } = useDashboardData(handleUnauthorized)

  return (
    <>
      <TopBar
        onReload={reload}
        onSignOut={() => {
          tokenStore.clear()
          onSignOut(null)
        }}
        busy={state.status === 'loading'}
      />

      <main className="page">
        {state.status === 'loading' && <p className="empty">불러오는 중…</p>}

        {state.status === 'error' && (
          <p className="alert alert--error">불러오지 못했습니다: {state.message}</p>
        )}

        {state.status === 'ready' && (
          <>
            <SummaryCards data={state.data} />
            <div className="panels">
              <DeployerTable deployers={state.data.deployers} />
              <StackPolicyTable policies={state.data.policies} />
              <HistoryTable entries={state.data.history} />
            </div>
          </>
        )}
      </main>
    </>
  )
}

export default function App() {
  const [token, setToken] = useState<string | null>(() => tokenStore.get())
  const [notice, setNotice] = useState<string | null>(null)

  if (token === null) {
    return (
      <LoginScreen
        notice={notice}
        onAuthenticated={(value) => {
          tokenStore.set(value)
          setNotice(null)
          setToken(value)
        }}
      />
    )
  }

  return (
    <Dashboard
      onSignOut={(message) => {
        setNotice(message)
        setToken(null)
      }}
    />
  )
}
