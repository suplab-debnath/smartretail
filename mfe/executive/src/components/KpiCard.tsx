import type { Trend, DirectionTrend } from '../types'

export interface KpiCardProps {
  label: string
  value: string
  trend: Trend | DirectionTrend
  color: 'green' | 'amber' | 'red' | 'neutral'
  subtitle?: string
}

const trendLabel: Record<string, string> = {
  IMPROVING: '▲ Improving',
  STABLE: '— Stable',
  DEGRADING: '▼ Degrading',
  INCREASING: '▲ Increasing',
  DECREASING: '▼ Decreasing',
}

const trendColor: Record<string, string> = {
  IMPROVING: 'text-green-600',
  STABLE: 'text-gray-500',
  DEGRADING: 'text-red-500',
  INCREASING: 'text-red-500',
  DECREASING: 'text-green-600',
}

const cardBorder: Record<KpiCardProps['color'], string> = {
  green: 'border-l-4 border-green-500',
  amber: 'border-l-4 border-amber-500',
  red: 'border-l-4 border-red-500',
  neutral: 'border-l-4 border-gray-300',
}

export function KpiCard({ label, value, trend, color, subtitle }: KpiCardProps) {
  return (
    <div className={`bg-white rounded-lg shadow p-6 ${cardBorder[color]}`}>
      <p className="text-sm font-medium text-gray-500 uppercase tracking-wide">{label}</p>
      <p className="mt-2 text-3xl font-bold text-gray-900">{value}</p>
      {subtitle && <p className="mt-1 text-sm text-gray-400">{subtitle}</p>}
      <p className={`mt-3 text-sm font-medium ${trendColor[trend]}`}>
        {trendLabel[trend] ?? trend}
      </p>
    </div>
  )
}
