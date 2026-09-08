import type { ApprovalHistoryEntry } from '../types'
import { Badge } from './Badge'
import { EmptyState, Panel } from './Panel'

function formatTimestamp(isoString: string): string {
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return isoString
  return date.toLocaleString(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function HistoryTable({ entries }: { entries: ApprovalHistoryEntry[] }) {
  return (
    <Panel
      title="Approval History"
      description="누가 언제 무엇을 승인·거부했는지에 대한 감사 로그 (최근 50건)"
      count={entries.length}
    >
      {entries.length === 0 ? (
        <EmptyState>아직 기록된 결정이 없습니다.</EmptyState>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>시각</th>
                <th>deployer</th>
                <th>스택</th>
                <th>action</th>
                <th>결과</th>
                <th>결정자</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id}>
                  <td className="muted nowrap">{formatTimestamp(entry.decidedAt)}</td>
                  <td>
                    <span className="identity">{entry.deployer}</span>
                  </td>
                  <td className="nowrap">{entry.stack}</td>
                  <td className="muted nowrap">{entry.action}</td>
                  <td>
                    <Badge value={entry.result} />
                  </td>
                  <td className="nowrap">
                    {entry.decidedBy === null ? (
                      <span className="muted">—</span>
                    ) : entry.decidedBy === 'SYSTEM' ? (
                      <span className="muted">SYSTEM</span>
                    ) : (
                      <span className="identity">{entry.decidedBy}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}
