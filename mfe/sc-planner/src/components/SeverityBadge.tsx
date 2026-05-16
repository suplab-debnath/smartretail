import type { AlertSeverity } from '../types'

interface Props {
  severity: AlertSeverity
}

const colorMap: Record<AlertSeverity, string> = {
  CRITICAL: 'bg-red-100 text-red-700',
  HIGH: 'bg-amber-100 text-amber-700',
  MEDIUM: 'bg-yellow-100 text-yellow-700',
}

export function SeverityBadge({ severity }: Props) {
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold ${colorMap[severity]}`}>
      {severity}
    </span>
  )
}
