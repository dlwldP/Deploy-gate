import { useCallback, useEffect, useState } from 'react'
import { fetchDeployers, fetchHistory, fetchStackPolicies } from './api'
import { UnauthorizedError } from './auth'
import type { ApprovalHistoryEntry, DeployerSummary, StackPolicySummary } from './types'

export interface DashboardData {
  deployers: DeployerSummary[]
  policies: StackPolicySummary[]
  history: ApprovalHistoryEntry[]
}

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: DashboardData }

/**
 * Loads every panel's data in one pass so the summary can be derived from the same
 * snapshot the tables show, instead of three independently-timed fetches.
 */
export function useDashboardData(onUnauthorized: (message: string) => void) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [reloadToken, setReloadToken] = useState(0)

  const reload = useCallback(() => setReloadToken((value) => value + 1), [])

  useEffect(() => {
    let cancelled = false
    setState({ status: 'loading' })

    Promise.all([fetchDeployers(), fetchStackPolicies(), fetchHistory(50)])
      .then(([deployers, policies, history]) => {
        if (!cancelled) setState({ status: 'ready', data: { deployers, policies, history } })
      })
      .catch((error: unknown) => {
        if (cancelled) return
        if (error instanceof UnauthorizedError) {
          onUnauthorized(error.message)
          return
        }
        setState({ status: 'error', message: error instanceof Error ? error.message : String(error) })
      })

    return () => {
      cancelled = true
    }
  }, [reloadToken, onUnauthorized])

  return { state, reload }
}
