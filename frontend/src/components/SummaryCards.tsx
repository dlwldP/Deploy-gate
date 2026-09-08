import type { DashboardData } from '../useDashboardData'

/** Every figure here is derived from the same snapshot the tables below render. */
export function SummaryCards({ data }: { data: DashboardData }) {
  const gatedStacks = data.policies.filter((policy) => policy.approvalLevel !== 'NONE').length
  const approvers = data.deployers.filter((deployer) =>
    deployer.claims.some((claim) => claim.endsWith(':approve')),
  ).length
  const denied = data.history.filter((entry) => entry.result === 'DENIED').length

  const cards = [
    { label: 'Deployers', value: data.deployers.length, detail: `승인권자 ${approvers}명` },
    {
      label: 'Stacks',
      value: data.policies.length,
      detail: gatedStacks > 0 ? `승인 필요 ${gatedStacks}개` : '승인 게이트 없음',
    },
    { label: '최근 결정', value: data.history.length, detail: '감사 로그 기준' },
    { label: '거부', value: denied, detail: denied > 0 ? '확인 필요' : '없음', tone: denied > 0 ? 'bad' : undefined },
  ]

  return (
    <section className="summary">
      {cards.map((card) => (
        <article className="stat" key={card.label}>
          <p className="stat__label">{card.label}</p>
          <p className={card.tone === 'bad' ? 'stat__value stat__value--bad' : 'stat__value'}>{card.value}</p>
          <p className="stat__detail">{card.detail}</p>
        </article>
      ))}
    </section>
  )
}
