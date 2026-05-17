import { useState } from 'react'
import { DcSelector } from './DcSelector'
import { KpiRow } from './KpiRow'
import { AlertList } from './AlertList'
import { DataFreshnessIndicator } from './DataFreshnessIndicator'
import { useStoreManagerDashboard } from '../hooks/useStoreManagerDashboard'

export function StoreDashboard() {
  const [dcId, setDcId] = useState('DC-LONDON')
  const [page, setPage] = useState(0)

  const { data, loading, error, refresh } = useStoreManagerDashboard(dcId, page)

  function handleDcChange(newDcId: string) {
    setDcId(newDcId)
    setPage(0)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b border-gray-200 px-6 py-4">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-gray-900">Store Manager Dashboard</h1>
            <p className="text-sm text-gray-500 mt-0.5">SmartRetail · {dcId}</p>
          </div>
          <DcSelector value={dcId} onChange={handleDcChange} />
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-6 space-y-6">
        {loading && !data && (
          <div className="py-20 text-center text-gray-400">Loading dashboard…</div>
        )}

        {error && (
          <div className="rounded-md bg-red-50 border border-red-200 p-4 text-sm text-red-700">
            Failed to load dashboard: {error}
          </div>
        )}

        {data && (
          <>
            <KpiRow
              alertKpi={data.alertKpi}
              totalOnHandUnits={data.totalOnHandUnits}
              pendingReplenishmentCount={data.pendingReplenishmentCount}
              forecastCoveragePct={data.forecastCoveragePct}
            />

            <AlertList
              alerts={data.alerts}
              page={data.alertsPage}
              totalPages={data.alertsTotalPages}
              onPageChange={setPage}
            />

            <div className="flex justify-end">
              <DataFreshnessIndicator
                dataFreshness={data.dataFreshness}
                onRefresh={refresh}
              />
            </div>
          </>
        )}
      </main>
    </div>
  )
}
