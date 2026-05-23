type ShipmentStatus = 'PENDING' | 'CONFIRMED' | 'DISPATCHED' | 'DELIVERED' | 'COMPLETED' | 'EXCEPTION'

const STATUS_STYLES: Record<ShipmentStatus, string> = {
  PENDING:    'bg-gray-100 text-gray-700',
  CONFIRMED:  'bg-blue-100 text-blue-700',
  DISPATCHED: 'bg-amber-100 text-amber-700',
  DELIVERED:  'bg-green-100 text-green-700',
  COMPLETED:  'bg-emerald-100 text-emerald-700',
  EXCEPTION:  'bg-red-100 text-red-700',
}

interface Props {
  status: ShipmentStatus
}

export function ShipmentStatusBadge({ status }: Props) {
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLES[status] ?? 'bg-gray-100 text-gray-700'}`}>
      {status}
    </span>
  )
}
