const TONE_CLASS: Record<string, string> = {
  ALLOWED: 'badge badge--good',
  NONE: 'badge badge--good',
  PENDING: 'badge badge--warn',
  SINGLE_APPROVER: 'badge badge--warn',
  DENIED: 'badge badge--bad',
  DUAL_APPROVER: 'badge badge--strong',
}

const LABEL: Record<string, string> = {
  NONE: '즉시 승인',
  SINGLE_APPROVER: '승인 1인',
  DUAL_APPROVER: '승인 2인',
}

/** Colored pill for enum-ish values (approval level, decision). */
export function Badge({ value }: { value: string }) {
  return <span className={TONE_CLASS[value] ?? 'badge'}>{LABEL[value] ?? value}</span>
}
