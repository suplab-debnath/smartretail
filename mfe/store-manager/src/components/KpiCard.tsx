interface Props {
  label: string
  value: string | number
  subItems?: { label: string; value: number; color: string }[]
}

export function KpiCard({ label, value, subItems }: Props) {
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-5 shadow-sm">
      <p className="text-sm font-medium text-gray-500 truncate">{label}</p>
      <p className="mt-1 text-3xl font-semibold text-gray-900">{value}</p>
      {subItems && subItems.length > 0 && (
        <div className="mt-2 flex gap-3">
          {subItems.map(item => (
            <span key={item.label} className={`text-xs font-medium ${item.color}`}>
              {item.label}: {item.value}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
