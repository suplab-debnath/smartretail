const DC_OPTIONS = ['DC-LONDON', 'DC-MANCHESTER', 'DC-BIRMINGHAM']

interface Props {
  value: string
  onChange: (dcId: string) => void
}

export function DcSelector({ value, onChange }: Props) {
  return (
    <div className="flex items-center gap-2">
      <label htmlFor="dc-select" className="text-sm font-medium text-gray-700">
        Distribution Centre
      </label>
      <select
        id="dc-select"
        value={value}
        onChange={e => onChange(e.target.value)}
        className="border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        {DC_OPTIONS.map(dc => (
          <option key={dc} value={dc}>{dc}</option>
        ))}
      </select>
    </div>
  )
}
