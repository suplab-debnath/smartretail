import { useState } from 'react'
import { useExecutiveDashboard } from '../hooks/useExecutiveDashboard'
import { KpiCard } from './KpiCard'
import { MapeTrendChart } from './MapeTrendChart'
import { ForecastHistoryTable } from './ForecastHistoryTable'
import { StockoutChart } from './StockoutChart'
import { StockoutHistoryTable } from './StockoutHistoryTable'
import { CycleTimeChart } from './CycleTimeChart'
import { CycleTimeHistoryTable } from './CycleTimeHistoryTable'
import { SupplierRankingTable } from './SupplierRankingTable'
import { DeliveryHistogram } from './DeliveryHistogram'
import type { KpiCardProps } from './KpiCard'

type CardId = 'forecast' | 'stockout' | 'cycletime' | 'otd'

function mapeColor(mape: number): KpiCardProps['color'] {
  if (mape < 0.10) return 'green'
  if (mape <= 0.20) return 'amber'
  return 'red'
}

export function ExecutiveDashboard() {
  const { data, loading, error, lastUpdated } = useExecutiveDashboard()
  const [expanded, setExpanded] = useState<CardId | null>(null)

  function toggle(id: CardId) {
    setExpanded(prev => (prev === id ? null : id))
  }

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

  const { forecastAccuracy, stockoutFrequency, replenishmentCycleTime, onTimeDelivery, supplierPerformance } = data.kpis
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

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-4">

        {/* KPI Cards — 2×2 grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
          <KpiCard
            label="Forecast Accuracy"
            value={`${accuracyPct}%`}
            trend={forecastAccuracy.trend}
            color={mapeColor(forecastAccuracy.latestMape)}
            subtitle={`MAPE: ${(forecastAccuracy.latestMape * 100).toFixed(2)}%`}
            onClick={() => toggle('forecast')}
            isExpanded={expanded === 'forecast'}
          />
          <KpiCard
            label="Stockout Frequency (30d)"
            value={String(stockoutFrequency.last30Days)}
            trend={stockoutFrequency.trend}
            color={stockoutFrequency.last30Days === 0 ? 'green' : stockoutFrequency.last30Days > 10 ? 'red' : 'amber'}
            subtitle="CRITICAL alerts raised"
            onClick={() => toggle('stockout')}
            isExpanded={expanded === 'stockout'}
          />
          <KpiCard
            label="Replenishment Cycle Time"
            value={`${replenishmentCycleTime.averageDays}d`}
            trend={replenishmentCycleTime.trend}
            color={replenishmentCycleTime.averageDays <= 3 ? 'green' : replenishmentCycleTime.averageDays <= 5 ? 'amber' : 'red'}
            subtitle="Avg days DRAFT → DISPATCHED"
            onClick={() => toggle('cycletime')}
            isExpanded={expanded === 'cycletime'}
          />
          <KpiCard
            label="On-Time Delivery"
            value={`${(onTimeDelivery.rate * 100).toFixed(1)}%`}
            trend={onTimeDelivery.trend}
            color={onTimeDelivery.rate >= 0.90 ? 'green' : onTimeDelivery.rate >= 0.75 ? 'amber' : 'red'}
            subtitle="Aggregate supplier OTD"
            onClick={() => toggle('otd')}
            isExpanded={expanded === 'otd'}
          />
        </div>

        {/* Expandable detail panels */}

        {expanded === 'forecast' && (
          <div className="space-y-4 animate-fadeIn">
            <MapeTrendChart history={forecastAccuracy.history} />
            <ForecastHistoryTable history={forecastAccuracy.history} />
          </div>
        )}

        {expanded === 'stockout' && (
          <div className="space-y-4 animate-fadeIn">
            <StockoutChart history={stockoutFrequency.history} />
            <StockoutHistoryTable history={stockoutFrequency.history} />
          </div>
        )}

        {expanded === 'cycletime' && (
          <div className="space-y-4 animate-fadeIn">
            <CycleTimeChart
              history={replenishmentCycleTime.history}
              overallAverage={replenishmentCycleTime.averageDays}
            />
            <CycleTimeHistoryTable history={replenishmentCycleTime.history} />
          </div>
        )}

        {expanded === 'otd' && (
          <div className="space-y-4 animate-fadeIn">
            <SupplierRankingTable suppliers={supplierPerformance} />
            <DeliveryHistogram suppliers={supplierPerformance} />
          </div>
        )}

      </main>
    </div>
  )
}
