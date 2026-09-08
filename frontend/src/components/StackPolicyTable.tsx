import type { StackPolicySummary } from '../types'
import { Badge } from './Badge'
import { EmptyState, Panel } from './Panel'

export function StackPolicyTable({ policies }: { policies: StackPolicySummary[] }) {
  return (
    <Panel
      title="Stack Policies"
      description="스택별 배포에 필요한 claim과 승인 레벨"
      count={policies.length}
    >
      {policies.length === 0 ? (
        <EmptyState>등록된 정책이 없습니다.</EmptyState>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th className="table__col--name">스택</th>
              <th>필요 claim</th>
              <th className="table__col--right">승인 레벨</th>
            </tr>
          </thead>
          <tbody>
            {policies.map((policy) => (
              <tr key={policy.id}>
                <td>
                  <span className="identity">{policy.stackName}</span>
                </td>
                <td>
                  <code className="chip">{policy.requiredClaim}</code>
                </td>
                <td className="table__col--right">
                  <Badge value={policy.approvalLevel} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  )
}
