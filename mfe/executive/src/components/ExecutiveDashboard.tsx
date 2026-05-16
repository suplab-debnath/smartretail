import { useExecutiveDashboard } from '../hooks/useExecutiveDashboard'
import { KpiCard } from './KpiCard'
import { MapeTrendChart } from './MapeTrendChart'
import { ForecastHistoryTable } from './ForecastHistoryTable'
import { StockoutChart } from './StockoutChart'
import { StockoutHistoryTable } from './StockoutHistoryTable'
import { CycleTimeChart } from './CycleTimeChart'
import { CycleTimeHistoryTable } from './CycleTimeHistoryTable'
import type { KpiCardProps } from './KpiCard'

function mapeColor(mape: number): KpiCardProps['color'] {
  if (mape < 0.10) return 'green'
  if (mape <= 0.20) return 'amber'
  return 'red'
}

export function ExecutiveDashboard() {
  const { data, loading, error, lastUpdated } = useExecutiveDashboard()

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-gray-500">Loading dashboard…</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-red-500">Error loading dashboard: {error}</div>
      </div>
    )
  }

  if (!data) return null

  const { forecastAccuracy, stockoutFrequency, replenishmentCycleTime } = data.kpis
  const accuracyPct = ((1 - forecastAccuracy.latestMape) * 100).toFixed(1)

  return (
    <div className="min-h-screen bg-gray-100">
      <header className="bg-white shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Executive Insights Dashboard</h1>
            <p className="text-sm text-gray-500">SmartRetail · Demand Forecasting & Supply Chain</p>
          </div>
          {lastUpdated && (
            <p className="text-xs text-gray-400">
              Last updated: {lastUpdated.toLocaleTimeString()}
            </p>
          )}
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        {/* KPI Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <KpiCard
            label="Forecast Accuracy"
            value={`${accuracyPct}%`}
            trend={forecastAccuracy.trend}
            color={mapeColor(forecastAccuracy.latestMape)}
            subtitle={`MAPE: ${(forecastAccuracy.latestMape * 100).toFixed(2)}%`}
          />
          <KpiCard
            label="Stockout Frequency (30d)"
            value={String(stockoutFrequency.last30Days)}
            trend={stockoutFrequency.trend}
            color={stockoutFrequency.last30Days === 0 ? 'green' : stockoutFrequency.last30Days > 10 ? 'red' : 'amber'}
            subtitle="CRITICAL alerts raised"
          />
          <KpiCard
            label="Replenishment Cycle Time"
            value={`${replenishmentCycleTime.averageDays}d`}
            trend={replenishmentCycleTime.trend}
            color={replenishmentCycleTime.averageDays <= 3 ? 'green' : replenishmentCycleTime.averageDays <= 5 ? 'amber' : 'red'}
            subtitle="Avg days DRAFT → DISPATCHED"
          />
        </div>

        {/* MAPE Trend Chart */}
        <MapeTrendChart history={forecastAccuracy.history} />

        {/* Forecast Accuracy History Table */}
        <ForecastHistoryTable history={forecastAccuracy.history} />

        {/* Stockout Frequency Charts */}
        <StockoutChart history={stockoutFrequency.history} />
        <StockoutHistoryTable history={stockoutFrequency.history} />

        {/* Replenishment Cycle Time Charts */}
        <CycleTimeChart
          history={replenishmentCycleTime.history}
          overallAverage={replenishmentCycleTime.averageDays}
        />
        <CycleTimeHistoryTable history={replenishmentCycleTime.history} />
      </main>
    </div>
  )
}
