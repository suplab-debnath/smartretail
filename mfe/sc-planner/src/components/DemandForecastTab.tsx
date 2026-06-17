import { useState } from 'react'
import {
  Area, Line, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip,
  Legend, ResponsiveContainer, ComposedChart,
} from 'recharts'
import { ErrorBanner, Tooltip } from '@smartretail/auth'
import { useForecast } from '../hooks/useForecast'
import type { ForecastBand } from '../types'

type Horizon = 7 | 14 | 30

function ForecastAccuracyBadge({ mape }: { mape: number }) {
  const pct = (mape * 100).toFixed(1)
  let cls = 'bg-green-100 text-green-700'
  let icon = '✓'
  if (mape > 0.20) { cls = 'bg-red-100 text-red-700'; icon = '✗' }
  else if (mape > 0.10) { cls = 'bg-amber-100 text-amber-700'; icon = '~' }
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold ${cls}`}>
      <Tooltip term="MAPE">MAPE</Tooltip>: {pct}% {icon}
    </span>
  )
}

function formatDate(dateStr: string) {
  return dateStr.slice(5)
}

export function DemandForecastTab() {
  const [skuId, setSkuId] = useState('SKU-BEV-001')
  const [skuInput, setSkuInput] = useState('SKU-BEV-001')
  const [dcId, setDcId] = useState('DC-LONDON')
  const [horizon, setHorizon] = useState<Horizon>(30)
  const { data, loading, error, refetch } = useForecast(skuId, dcId, horizon)

  function applySkuId() {
    setSkuId(skuInput.trim() || 'SKU-BEV-001')
  }

  const chartData = (data?.bands ?? []).map((b: ForecastBand) => ({
    date: formatDate(b.forecastDate),
    p10: b.p10,
    p50: b.p50,
    p90: b.p90,
    actual: b.actualUnits,
  }))

  return (
    <div className="space-y-4">
      <ErrorBanner error={error} onRetry={refetch} />

      {/* Controls */}
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex items-center gap-2">
          <label className="text-sm font-medium text-gray-700">
            <Tooltip term="SKU">SKU</Tooltip>:
          </label>
          <input
            type="text"
            value={skuInput}
            onChange={e => setSkuInput(e.target.value)}
            onBlur={applySkuId}
            onKeyDown={e => e.key === 'Enter' && applySkuId()}
            className="border border-gray-300 rounded px-2 py-1 text-sm font-mono w-36"
          />
        </div>
        <div className="flex items-center gap-2">
          <label className="text-sm font-medium text-gray-700">
            <Tooltip term="DC">DC</Tooltip>:
          </label>
          <select
            value={dcId}
            onChange={e => setDcId(e.target.value)}
            className="border border-gray-300 rounded px-2 py-1 text-sm"
          >
            {['DC-LONDON', 'DC-MANCHESTER', 'DC-BIRMINGHAM'].map(dc => (
              <option key={dc} value={dc}>{dc}</option>
            ))}
          </select>
        </div>
        <div className="flex items-center gap-2">
          <label className="text-sm font-medium text-gray-700">Horizon:</label>
          <div className="flex rounded overflow-hidden border border-gray-300">
            {([7, 14, 30] as Horizon[]).map(h => (
              <button
                key={h}
                onClick={() => setHorizon(h)}
                className={`px-3 py-1 text-sm ${horizon === h ? 'bg-blue-600 text-white' : 'bg-white text-gray-700 hover:bg-gray-50'}`}
              >
                {h}d
              </button>
            ))}
          </div>
        </div>
        {data && <ForecastAccuracyBadge mape={data.latestMape} />}
      </div>

      {loading && !data ? (
        <div className="p-8 text-gray-500">Loading forecast…</div>
      ) : data ? (
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-base font-semibold text-gray-800 mb-4">
            Demand Forecast — {data.skuId} / {data.dcId} ({data.horizonDays}d)
          </h2>
          <ResponsiveContainer width="100%" height={320}>
            <ComposedChart data={chartData} margin={{ top: 8, right: 24, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} interval={Math.floor(chartData.length / 7)} />
              <YAxis tick={{ fontSize: 11 }} />
              <RechartsTooltip />
              <Legend
                formatter={(value) => {
                  const labels: Record<string, string> = {
                    P90: 'P90 — Optimistic',
                    P50: 'P50 — Expected Demand',
                    P10: 'P10 — Cautious',
                    Actual: 'Actual Units',
                  }
                  return labels[value] ?? value
                }}
              />
              <Area type="monotone" dataKey="p90" stroke="#93c5fd" fill="#dbeafe" fillOpacity={0.5} name="P90" />
              <Area type="monotone" dataKey="p50" stroke="#60a5fa" fill="#bfdbfe" fillOpacity={0.5} name="P50" />
              <Area type="monotone" dataKey="p10" stroke="#93c5fd" fill="#dbeafe" fillOpacity={0.3} name="P10" />
              <Line type="monotone" dataKey="actual" stroke="#9ca3af" strokeWidth={2} dot={false} name="Actual" />
            </ComposedChart>
          </ResponsiveContainer>
          <div className="mt-3 flex gap-4 text-xs text-gray-500">
            <span><Tooltip term="P10">P10</Tooltip> — cautious estimate</span>
            <span><Tooltip term="P50">P50</Tooltip> — expected demand</span>
            <span><Tooltip term="P90">P90</Tooltip> — optimistic estimate</span>
          </div>
          <p className="mt-2 text-xs text-gray-400">
            Data as of {new Date(data.dataFreshness).toLocaleTimeString()}
          </p>
        </div>
      ) : null}
    </div>
  )
}
