import { Tooltip } from '@smartretail/auth'
import { KpiCard } from './KpiCard'
import type { AlertKpi } from '../types'

interface Props {
  alertKpi: AlertKpi
  totalOnHandUnits: number
  pendingReplenishmentCount: number
  forecastCoveragePct: number
}

export function KpiRow({ alertKpi, totalOnHandUnits, pendingReplenishmentCount, forecastCoveragePct }: Props) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <KpiCard
        label="Low Stock Alerts"
        value={alertKpi.totalActive}
        subItems={[
          { label: 'CRITICAL', value: alertKpi.criticalCount, color: 'text-red-600' },
          { label: 'HIGH',     value: alertKpi.highCount,     color: 'text-orange-600' },
          { label: 'MEDIUM',   value: alertKpi.mediumCount,   color: 'text-yellow-600' },
        ]}
      />
      <KpiCard
        label={<Tooltip term="ON_HAND">On-Hand Units</Tooltip>}
        value={totalOnHandUnits.toLocaleString()}
      />
      <KpiCard
        label="Pending Replenishment Orders"
        value={pendingReplenishmentCount}
      />
      <KpiCard
        label={<Tooltip term="FORECAST_COVERAGE">Forecast Coverage</Tooltip>}
        value={`${forecastCoveragePct.toFixed(1)}%`}
      />
    </div>
  )
}
