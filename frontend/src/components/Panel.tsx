import type { ReactNode } from 'react'

export function Panel({
  title,
  description,
  count,
  children,
}: {
  title: string
  description: string
  count?: number
  children: ReactNode
}) {
  return (
    <section className="panel">
      <header className="panel__header">
        <div className="panel__heading">
          <h2>{title}</h2>
          {count !== undefined && <span className="panel__count">{count}</span>}
        </div>
        <p className="panel__description">{description}</p>
      </header>
      <div className="panel__body">{children}</div>
    </section>
  )
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>
}
