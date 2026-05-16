interface Props {
  upliftPercent: number
  setUpliftPercent: (v: number) => void
}

export function ForecastAdjustmentTab({ upliftPercent, setUpliftPercent }: Props) {
  return (
    <div className="max-w-lg space-y-6">
      <h2 className="text-lg font-semibold text-gray-800">Forecast Adjustment Controls</h2>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Promotional Uplift (%)
        </label>
        <div className="flex items-center gap-3">
          <input
            type="number"
            value={upliftPercent}
            onChange={e => setUpliftPercent(Math.max(0, Math.min(100, Number(e.target.value))))}
            min={0}
            max={100}
            className="border border-gray-300 rounded px-3 py-2 text-sm w-28"
          />
          {upliftPercent > 0 && (
            <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-amber-100 text-amber-700">
              Promo uplift: +{upliftPercent}%
            </span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Promotion Start</label>
          <input
            type="date"
            className="border border-gray-300 rounded px-3 py-2 text-sm w-full"
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Promotion End</label>
          <input
            type="date"
            className="border border-gray-300 rounded px-3 py-2 text-sm w-full"
          />
        </div>
      </div>

      <div>
        <button
          onClick={() => setUpliftPercent(0)}
          disabled={upliftPercent === 0}
          className="px-4 py-2 text-sm border border-gray-300 rounded text-gray-700 hover:bg-gray-50 disabled:opacity-40"
        >
          Reset Uplift
        </button>
      </div>

      <p className="text-xs text-gray-500">
        Uplift is applied to the P50 forecast line in the Demand Forecast tab.
      </p>
    </div>
  )
}
