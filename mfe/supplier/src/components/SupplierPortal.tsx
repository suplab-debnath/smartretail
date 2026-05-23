import { useAuth } from '@smartretail/auth'
import { useSupplierOrders } from '../hooks/useSupplierOrders'
import { OrderListTab } from './OrderListTab'
import { DataFreshnessIndicator } from './DataFreshnessIndicator'

export function SupplierPortal() {
  const { isLoading: authLoading, isAuthenticated, signIn, user } = useAuth()
  const { orders, dataFreshness, isLoading, error, refresh } = useSupplierOrders()

  if (authLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-gray-500">Loading…</div>
      </div>
    )
  }

  if (!isAuthenticated) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-gray-50 gap-4">
        <h1 className="text-2xl font-semibold text-gray-800">SmartRetail Supplier Portal</h1>
        <p className="text-gray-500">Sign in to view your purchase orders and shipment status</p>
        <button
          onClick={() => signIn()}
          className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
        >
          Sign In
        </button>
      </div>
    )
  }

  const exceptionCount = orders.filter(o => o.shipmentStatus === 'EXCEPTION').length

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200 px-6 py-4">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-gray-900">Supplier Portal</h1>
            {user?.email && (
              <p className="text-sm text-gray-500">{user.email}</p>
            )}
          </div>
          <div className="flex items-center gap-4">
            {exceptionCount > 0 && (
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700">
                {exceptionCount} Exception{exceptionCount > 1 ? 's' : ''}
              </span>
            )}
            <DataFreshnessIndicator dataFreshness={dataFreshness} onRefresh={refresh} />
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="max-w-7xl mx-auto px-6 py-8">
        {/* Summary cards */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-8">
          {(['PENDING', 'CONFIRMED', 'DISPATCHED', 'EXCEPTION'] as const).map(status => {
            const count = orders.filter(o => o.shipmentStatus === status).length
            const isException = status === 'EXCEPTION'
            return (
              <div
                key={status}
                className={`bg-white rounded-lg border p-4 ${isException && count > 0 ? 'border-red-200' : 'border-gray-200'}`}
              >
                <p className="text-sm text-gray-500">{status}</p>
                <p className={`text-2xl font-bold mt-1 ${isException && count > 0 ? 'text-red-600' : 'text-gray-900'}`}>
                  {count}
                </p>
              </div>
            )
          })}
        </div>

        {/* Order table */}
        <div className="bg-white rounded-lg border border-gray-200 p-6">
          <h2 className="text-base font-semibold text-gray-900 mb-4">Purchase Orders</h2>

          {error && (
            <div className="mb-4 p-3 rounded bg-red-50 text-red-700 text-sm">
              Failed to load orders: {error}
            </div>
          )}

          {isLoading ? (
            <div className="flex items-center justify-center h-48 text-gray-400">
              Loading orders…
            </div>
          ) : (
            <OrderListTab orders={orders} />
          )}
        </div>
      </main>
    </div>
  )
}
