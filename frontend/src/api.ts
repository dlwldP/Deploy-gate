import { tokenStore, UnauthorizedError } from './auth'
import type { ApprovalHistoryEntry, DeployerSummary, StackPolicySummary } from './types'

async function getJson<T>(path: string, token?: string): Promise<T> {
  const bearer = token ?? tokenStore.get()
  const response = await fetch(path, {
    headers: bearer ? { Authorization: `Bearer ${bearer}` } : {},
  })

  if (response.status === 401 || response.status === 403) {
    throw new UnauthorizedError(
      response.status === 403
        ? '이 토큰에는 admin:read 권한이 없습니다.'
        : '토큰이 유효하지 않습니다.',
    )
  }
  if (!response.ok) {
    throw new Error(`${path} 요청이 ${response.status} 로 실패했습니다.`)
  }
  return (await response.json()) as T
}

export function fetchDeployers(token?: string): Promise<DeployerSummary[]> {
  return getJson<DeployerSummary[]>('/admin/deployers', token)
}

export function fetchStackPolicies(token?: string): Promise<StackPolicySummary[]> {
  return getJson<StackPolicySummary[]>('/admin/stack-policies', token)
}

export function fetchHistory(limit = 50, token?: string): Promise<ApprovalHistoryEntry[]> {
  return getJson<ApprovalHistoryEntry[]>(`/approval/history?limit=${limit}`, token)
}

/** Verifies a token before we store it, so the login screen can show a real error. */
export async function verifyToken(token: string): Promise<void> {
  await fetchDeployers(token)
}
