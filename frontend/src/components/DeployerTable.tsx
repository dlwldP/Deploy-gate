import type { DeployerSummary } from '../types'
import { EmptyState, Panel } from './Panel'

/** Splits "stack:ProdAlbStack:approve" so the verb can be highlighted separately. */
function claimVerb(claim: string): string | null {
  const parts = claim.split(':')
  return parts.length >= 3 ? parts[parts.length - 1] : null
}

export function DeployerTable({ deployers }: { deployers: DeployerSummary[] }) {
  return (
    <Panel
      title="Deployers"
      description="등록된 사용자와 각자가 보유한 claim"
      count={deployers.length}
    >
      {deployers.length === 0 ? (
        <EmptyState>등록된 deployer가 없습니다.</EmptyState>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th className="table__col--name">이름</th>
              <th>claims</th>
            </tr>
          </thead>
          <tbody>
            {deployers.map((deployer) => (
              <tr key={deployer.id}>
                <td>
                  <span className="identity">{deployer.name}</span>
                </td>
                <td>
                  {deployer.claims.length === 0 ? (
                    <span className="muted">claim 없음</span>
                  ) : (
                    <div className="chips">
                      {[...deployer.claims].sort().map((claim) => (
                        <code
                          className={claimVerb(claim) === 'approve' ? 'chip chip--approve' : 'chip'}
                          key={claim}
                        >
                          {claim}
                        </code>
                      ))}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  )
}
