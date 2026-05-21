import { useState, useEffect } from 'react'
import { useAuth, Tooltip } from '@smartretail/auth'
import { useScPlannerDashboard } from '../hooks/useScPlannerDashboard'
import { ExceptionQueueTab } from './ExceptionQueueTab'
import { InventoryOverviewTab } from './InventoryOverviewTab'
import { DemandForecastTab } from './DemandForecastTab'
import { StockoutRiskTab } from './StockoutRiskTab'
import { ApprovalWorkflowsTab } from './ApprovalWorkflowsTab'
import { SupplierOrderTrackingTab } from './SupplierOrderTrackingTab'
import { SupplierScorecardTab } from './SupplierScorecardTab'
import { ReplenishmentTriggerModal } from './ReplenishmentTriggerModal'
import { DemoTab } from './DemoTab'

type TabId =
  | 'exceptions'
  | 'inventory'
  | 'forecast'
  | 'stockout'
  | 'approvals'
  | 'supplier-orders'
  | 'scorecard'
  | 'demo'

interface TabDef {
  id: TabId
  label: string
}

const TABS: TabDef[] = [
  { id: 'exceptions',          label: 'Exception Queue'      },
  { id: 'inventory',           label: 'Inventory Overview'   },
  { id: 'forecast',            label: 'Demand Forecast'      },
  { id: 'stockout',            label: 'Stockout Risk'        },
  { id: 'approvals',           label: 'Approvals'            },
  { id: 'supplier-orders',     label: 'Supplier Orders'      },
  { id: 'scorecard',           label: 'Supplier Scorecard'   },
  { id: 'demo',                label: 'Demo'                 },
]

interface TriggerTarget {
  skuId: string
  dcId: string
}

export function ScPlannerConsole() {
  const { isAuthenticated, isLoading: authLoading, signIn, signOut, user, hasRole } = useAuth()
  const { data: dashData, loading: dashLoading, lastUpdated } = useScPlannerDashboard()

  const [activeTab, setActiveTab] = useState<TabId>('exceptions')
  const [visitedTabs, setVisitedTabs] = useState<Set<TabId>>(new Set(['exceptions']))
  const [triggerTarget, setTriggerTarget] = useState<TriggerTarget | null>(null)

  useEffect(() => {
    if (!authLoading && !isAuthenticated) {
      signIn()
    }
  }, [authLoading, isAuthenticated, signIn])

  function switchTab(id: TabId) {
    setActiveTab(id)
    setVisitedTabs(prev => new Set(prev).add(id))
  }

  function handleTriggerReplenishment(skuId: string, dcId: string) {
    setTriggerTarget({ skuId, dcId })
  }

  if (authLoading || !isAuthenticated) {
    return (
      <div className="flex items-center justify-center h-screen bg-gray-100">
        <div className="text-gray-500">
          {authLoading ? 'Checking authentication…' : 'Redirecting to login…'}
        </div>
      </div>
    )
  }

  if (!hasRole('SC_PLANNER') && !hasRole('ADMIN')) {
    return (
      <div className="flex items-center justify-center h-screen bg-gray-100">
        <div className="text-red-500">Access Denied: SC_PLANNER or ADMIN role required.</div>
      </div>
    )
  }

  const alertBadge = dashData?.activeAlertCount ?? 0
  const approvalBadge = dashData?.pendingApprovalCount ?? 0

  return (
    <div className="min-h-screen bg-gray-100">
      {/* Header */}
      <header className="bg-white shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">SC Planner Console</h1>
            <p className="text-sm text-gray-500">SmartRetail · Supply Chain Planning</p>
          </div>
          <div className="flex items-center gap-4">
            {dashData?.forecastAccuracy && (
              <div className="text-xs text-gray-500">
                <Tooltip term="MAPE">Forecast MAPE</Tooltip>:{' '}
                <span className={
                  dashData.forecastAccuracy.latestMape < 0.10 ? 'text-green-600 font-semibold' :
                  dashData.forecastAccuracy.latestMape <= 0.20 ? 'text-amber-600 font-semibold' :
                  'text-red-600 font-semibold'
                }>
                  {(dashData.forecastAccuracy.latestMape * 100).toFixed(1)}%
                </span>
                {' '}— {dashData.forecastAccuracy.status === 'WITHIN_THRESHOLD' ? 'Within threshold' : 'Above threshold'}
              </div>
            )}
            {lastUpdated && (
              <p className="text-xs text-gray-400">
                Last updated: {lastUpdated.toLocaleTimeString()}
              </p>
            )}
            <div className="flex flex-col items-end">
              <span className="text-xs text-gray-600 font-medium">{user?.email}</span>
              <button onClick={signOut} className="text-xs text-blue-600 hover:text-blue-800 underline">
                Sign out
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Tab bar */}
      <div className="bg-white border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <nav className="flex overflow-x-auto" aria-label="Tabs">
            {TABS.map(tab => {
              const isActive = activeTab === tab.id
              let badge: number | null = null
              if (tab.id === 'exceptions') badge = alertBadge
              if (tab.id === 'approvals') badge = approvalBadge

              return (
                <button
                  key={tab.id}
                  onClick={() => switchTab(tab.id)}
                  className={[
                    'relative whitespace-nowrap px-4 py-3 text-sm font-medium border-b-2 transition-colors',
                    isActive
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300',
                  ].join(' ')}
                >
                  {tab.label}
                  {badge !== null && badge > 0 && !dashLoading && (
                    <span className="ml-1.5 inline-flex items-center justify-center w-5 h-5 rounded-full bg-red-500 text-white text-xs font-bold">
                      {badge > 99 ? '99+' : badge}
                    </span>
                  )}
                </button>
              )
            })}
          </nav>
        </div>
      </div>

      {/* Tab panels */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        {visitedTabs.has('exceptions') && (
          <div className={activeTab === 'exceptions' ? '' : 'hidden'}>
            <ExceptionQueueTab onTriggerReplenishment={handleTriggerReplenishment} />
          </div>
        )}
        {visitedTabs.has('inventory') && (
          <div className={activeTab === 'inventory' ? '' : 'hidden'}>
            <InventoryOverviewTab />
          </div>
        )}
        {visitedTabs.has('forecast') && (
          <div className={activeTab === 'forecast' ? '' : 'hidden'}>
            <DemandForecastTab />
          </div>
        )}
        {visitedTabs.has('stockout') && (
          <div className={activeTab === 'stockout' ? '' : 'hidden'}>
            <StockoutRiskTab onTriggerReplenishment={handleTriggerReplenishment} />
          </div>
        )}
        {visitedTabs.has('approvals') && (
          <div className={activeTab === 'approvals' ? '' : 'hidden'}>
            <ApprovalWorkflowsTab />
          </div>
        )}
        {visitedTabs.has('supplier-orders') && (
          <div className={activeTab === 'supplier-orders' ? '' : 'hidden'}>
            <SupplierOrderTrackingTab />
          </div>
        )}
        {visitedTabs.has('scorecard') && (
          <div className={activeTab === 'scorecard' ? '' : 'hidden'}>
            <SupplierScorecardTab />
          </div>
        )}
        {visitedTabs.has('demo') && (
          <div className={activeTab === 'demo' ? '' : 'hidden'}>
            <DemoTab onSwitchToApprovals={() => switchTab('approvals')} />
          </div>
        )}
      </main>

      {/* Replenishment trigger modal */}
      {triggerTarget && (
        <ReplenishmentTriggerModal
          skuId={triggerTarget.skuId}
          dcId={triggerTarget.dcId}
          onClose={() => setTriggerTarget(null)}
          onSuccess={(_poId) => {
            setTriggerTarget(null)
          }}
        />
      )}
    </div>
  )
}
