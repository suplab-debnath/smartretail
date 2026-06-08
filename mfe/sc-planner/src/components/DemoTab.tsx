import { useState, useRef, useEffect, useCallback } from 'react'
import type { InventoryPosition, InventoryPositionListResponse, PurchaseOrder, PurchaseOrderListResponse } from '../types'
import { getApiBase } from '@smartretail/auth'
import { useSuppliers } from '../hooks/useSuppliers'
import { ReplenishmentFlowDiagram } from './ReplenishmentFlowDiagram'

interface FormValues {
  storeId: string
  skuId: string
  dcId: string
  quantity: string
  unitPrice: string
}

type DemoPhase = 'idle' | 'injecting' | 'polling' | 'found' | 'approving' | 'done' | 'timeout'

// Human-readable names for demo SKUs
const SKU_NAMES: Record<string, string> = {
  'SKU-SNK-002': 'Pringles',
}

const PIPELINE_STEPS = [
  { label: 'Checkout confirmed', hint: 'Store till transaction received and validated' },
  { label: 'Warehouse stock updated', hint: 'DC inventory level automatically decremented' },
  { label: 'Low-stock alert sent', hint: 'Stock fell below reorder threshold — alert published to event bus' },
  { label: 'Reorder created', hint: 'Replenishment Engine raised a Purchase Order for your approval' },
]

const DC_OPTIONS = [
  { value: 'DC-LONDON', label: 'London DC' },
  { value: 'DC-MANCHESTER', label: 'Manchester DC' },
  { value: 'DC-BIRMINGHAM', label: 'Birmingham DC' },
]

// SKU-SNK-002 at DC-LONDON: auto_approve_threshold = £0 → every PO lands in PENDING_APPROVAL
// on_hand (35) is already below reorder_point (80), so any sale triggers the full pipeline
const DEFAULT_FORM: FormValues = {
  storeId: 'STORE-001',
  skuId: 'SKU-SNK-002',
  dcId: 'DC-LONDON',
  quantity: '1',
  unitPrice: '9.75',
}

const MAX_POLL_ATTEMPTS = 12   // 24 s at 2 s intervals

interface Props {
  onSwitchToApprovals: () => void
  onDataChanged: () => void
}

export function DemoTab({ onSwitchToApprovals, onDataChanged }: Props) {
  const [form, setForm] = useState<FormValues>(DEFAULT_FORM)
  const [phase, setPhase] = useState<DemoPhase>('idle')
  const [completedSteps, setCompleted] = useState(0)
  const [foundPO, setFoundPO] = useState<PurchaseOrder | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [position, setPosition] = useState<InventoryPosition | null>(null)
  const supplierMap = useSuppliers()

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const snapshotRef = useRef<Set<string>>(new Set())
  const attemptRef = useRef(0)

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current) }, [])

  // Fetch inventory position whenever SKU / DC changes (idle only)
  useEffect(() => {
    if (phase !== 'idle' || !form.skuId || !form.dcId) return
    let cancelled = false
    fetch(`${getApiBase()}/v1/inventory/positions?skuId=${encodeURIComponent(form.skuId)}&dcId=${encodeURIComponent(form.dcId)}`, {
      headers: { 'X-Dev-Role': 'SC_PLANNER' },
    })
      .then(r => r.ok ? r.json() as Promise<InventoryPositionListResponse> : null)
      .then(data => { if (!cancelled) setPosition(data?.positions[0] ?? null) })
      .catch(() => { if (!cancelled) setPosition(null) })
    return () => { cancelled = true }
  }, [form.skuId, form.dcId, phase])

  function stopPolling() {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null }
  }

  function reset() {
    stopPolling()
    setPhase('idle')
    setCompleted(0)
    setFoundPO(null)
    setError(null)
    attemptRef.current = 0
    snapshotRef.current = new Set()
  }

  const checkForNewPO = useCallback(async () => {
    attemptRef.current += 1
    if (attemptRef.current > MAX_POLL_ATTEMPTS) {
      stopPolling()
      setPhase('timeout')
      return
    }
    try {
      const res = await fetch(`${getApiBase()}/v1/replenishment/orders?status=PENDING_APPROVAL&size=20`, {
        headers: { 'X-Dev-Role': 'SC_PLANNER' },
      })
      if (!res.ok) return
      const data: PurchaseOrderListResponse = await res.json()
      const newPO = data.orders.find(o => !snapshotRef.current.has(o.poId))
      if (newPO) {
        stopPolling()
        setCompleted(4)
        setFoundPO(newPO)
        setPhase('found')
        onDataChanged()   // exception queue + inventory are now stale
      }
    } catch {
      // keep polling
    }
  }, [onDataChanged])

  async function handleInject() {
    setError(null)
    setPhase('injecting')
    setCompleted(0)

    try {
      const snap = await fetch(`${getApiBase()}/v1/replenishment/orders?status=PENDING_APPROVAL&size=100`, {
        headers: { 'X-Dev-Role': 'SC_PLANNER' },
      })
      if (snap.ok) {
        const data: PurchaseOrderListResponse = await snap.json()
        snapshotRef.current = new Set(data.orders.map(o => o.poId))
      }
    } catch { /* proceed without snapshot */ }

    try {
      const res = await fetch(`${getApiBase()}/v1/ingest/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Dev-Role': 'SC_PLANNER' },
        body: JSON.stringify({
          transactionId: crypto.randomUUID(),
          storeId: form.storeId,
          skuId: form.skuId,
          dcId: form.dcId,
          quantity: parseInt(form.quantity, 10),
          unitPrice: parseFloat(form.unitPrice),
          channel: 'POS',
          eventTimestamp: new Date().toISOString(),
        }),
      })
      if (!res.ok) {
        const text = await res.text()
        setError(`Sale rejected: HTTP ${res.status} — ${text}`)
        setPhase('idle')
        return
      }
    } catch (e) {
      setError(`Network error: ${e instanceof Error ? e.message : 'Unknown'}`)
      setPhase('idle')
      return
    }

    setCompleted(1)
    setPhase('polling')
    attemptRef.current = 0

    setTimeout(() => setCompleted(prev => Math.max(prev, 2)), 2000)
    setTimeout(() => setCompleted(prev => Math.max(prev, 3)), 4000)

    pollRef.current = setInterval(checkForNewPO, 2000)
  }

  async function handleApprove() {
    if (!foundPO) return
    setPhase('approving')
    try {
      const res = await fetch(`${getApiBase()}/v1/replenishment/orders/${foundPO.poId}/approve`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Dev-Role': 'SC_PLANNER',
          'X-Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ version: foundPO.version }),
      })
      if (res.ok) {
        // Notify the SUP service so the order appears in the Supplier Orders tab
        await fetch(`${getApiBase()}/v1/supplier/orders`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Dev-Role': 'SC_PLANNER' },
          body: JSON.stringify({
            poId: foundPO.poId,
            supplierId: foundPO.supplierId,
            skuId: foundPO.skuId,
            dcId: foundPO.dcId,
            quantity: foundPO.quantity,
          }),
        }).catch(() => { /* non-fatal — order still approved */ })
        setPhase('done')
        onDataChanged()   // approvals + supplier orders are now stale
      } else if (res.status === 409) {
        setError('PO status changed concurrently — it may already be approved.')
        setPhase('found')
      } else {
        setError(`Approve failed: HTTP ${res.status}`)
        setPhase('found')
      }
    } catch (e) {
      setError(`Network error: ${e instanceof Error ? e.message : 'Unknown'}`)
      setPhase('found')
    }
  }

  const productName = SKU_NAMES[form.skuId] ?? form.skuId
  const isLowStock = position ? position.onHand <= position.reorderPoint : null
  const dcLabel = DC_OPTIONS.find(o => o.value === form.dcId)?.label ?? form.dcId

  return (
    <div className="space-y-6 max-w-2xl mx-auto">

      {/* Scenario card */}
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-5">
        <h2 className="text-base font-semibold text-blue-900 mb-2">
          Your bestseller just ran out at the warehouse — and nobody noticed
        </h2>
        <p className="text-sm text-blue-800 leading-relaxed">
          It's a busy Friday afternoon. A customer at your London flagship just bought the last pack
          of Pringles in the DC-London warehouse. Without an automated system, that gap sits
          undetected until Monday — by then, weekend shoppers walk in, find empty shelves, and head
          straight to a competitor.
        </p>
        <p className="mt-2 text-sm text-blue-800 leading-relaxed">
          This demo shows how your platform closes that loop in seconds. One checkout fires an
          automatic stock check, raises a low-inventory alert, and creates a supplier reorder —
          all before a single customer is turned away.
        </p>
        <p className="mt-2 text-xs text-blue-700">
          <span className="font-semibold">How it works:</span> The Pringles SKU is already
          running low in the DC. Even one more sale crosses the reorder threshold and triggers the
          full automated chain. After the system creates a Purchase Order, you'll approve it to
          send the restocking order to the supplier.
        </p>
      </div>

      {/* Step 1 — trigger */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-1">Step 1 — Simulate a store checkout</h3>
        <p className="text-xs text-gray-500 mb-4">
          A customer just bought an item at the store till. The SKU below is already running low
          in the warehouse — even this single sale is enough to trigger the automated response.
        </p>

        {/* Inventory context */}
        {position && phase === 'idle' && (
          <div className={[
            'flex items-start gap-2 mb-4 px-3 py-2 rounded text-xs font-medium border',
            isLowStock
              ? 'bg-red-50 border-red-200 text-red-700'
              : 'bg-green-50 border-green-200 text-green-700',
          ].join(' ')}>
            <span className="mt-0.5 shrink-0">{isLowStock ? '⚠' : '✓'}</span>
            <span>
              <span className="font-semibold">{productName}</span> at {dcLabel}:{' '}
              only <span className="font-semibold">{position.onHand} units</span> in the warehouse
              (reorder threshold: <span className="font-semibold">{position.reorderPoint}</span>
              {isLowStock
                ? ') — this SKU is already in the danger zone. Any sale fires the alert.'
                : ') — stock looks healthy.'
              }
            </span>
          </div>
        )}

        {/* Production context — shown always so the audience understands the real flow */}
        <div className="flex gap-2 mb-4 px-3 py-2 rounded bg-amber-50 border border-amber-200 text-xs text-amber-800">
          <span className="shrink-0 mt-0.5">ℹ</span>
          <span>
            <span className="font-semibold">Demo mode:</span> this button calls the Sales
            Ingestion Service directly to simulate a checkout. In production, POS aggregator
            sends each transaction to <span className="font-semibold">AWS Data Firehose</span>,
            which batches and delivers the events to SIS — the same pipeline then runs
            automatically from there.
          </span>
        </div>

        <div className="grid grid-cols-2 gap-4 mb-5">
          <label className="block">
            <span className="text-xs text-gray-500">Store</span>
            <input
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.storeId}
              onChange={e => setForm(f => ({ ...f, storeId: e.target.value }))}
              disabled={phase !== 'idle'}
            />
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Item sold</span>
            <input
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.skuId}
              onChange={e => setForm(f => ({ ...f, skuId: e.target.value }))}
              disabled={phase !== 'idle'}
            />
            {SKU_NAMES[form.skuId] && (
              <span className="text-xs text-gray-400 mt-0.5 block">{SKU_NAMES[form.skuId]}</span>
            )}
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Replenishing warehouse</span>
            <select
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.dcId}
              onChange={e => setForm(f => ({ ...f, dcId: e.target.value }))}
              disabled={phase !== 'idle'}
            >
              {DC_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Units sold</span>
            <input
              type="number" min="1"
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.quantity}
              onChange={e => setForm(f => ({ ...f, quantity: e.target.value }))}
              disabled={phase !== 'idle'}
            />
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Sale price per unit (£)</span>
            <input
              type="number" step="0.01" min="0"
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.unitPrice}
              onChange={e => setForm(f => ({ ...f, unitPrice: e.target.value }))}
              disabled={phase !== 'idle'}
            />
          </label>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={handleInject}
            disabled={phase !== 'idle'}
            className="px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {phase === 'injecting' ? 'Recording sale…' : 'Simulate store checkout'}
          </button>
          {phase !== 'idle' && (
            <button
              onClick={reset}
              className="px-4 py-2 border border-gray-300 text-gray-600 text-sm font-medium rounded hover:bg-gray-50 transition-colors"
            >
              Reset
            </button>
          )}
        </div>

        {error && (
          <p className="mt-3 text-sm text-red-600 bg-red-50 rounded p-2">{error}</p>
        )}
      </div>

      {/* Step 2 — pipeline */}
      {phase !== 'idle' && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-1">Step 2 — Automated replenishment pipeline</h3>
          <p className="text-xs text-gray-500 mb-4">
            No one pressed a button for this. Each step below happened automatically in response to the checkout.
          </p>

          <ReplenishmentFlowDiagram completedSteps={completedSteps} phase={phase} />

          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 mt-4">
            {PIPELINE_STEPS.map(({ label, hint }, i) => {
              const done = completedSteps > i
              const active = completedSteps === i && phase === 'polling'
              return (
                <div key={label} className="flex items-center gap-1.5" title={hint}>
                  <span className={[
                    'flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium',
                    done ? 'bg-green-100 text-green-700' :
                      active ? 'bg-blue-100 text-blue-700 animate-pulse' :
                        'bg-gray-100 text-gray-400',
                  ].join(' ')}>
                    <span>{done ? '✓' : active ? '⟳' : '○'}</span>
                    {label}
                  </span>
                  {i < PIPELINE_STEPS.length - 1 && (
                    <span className="text-gray-300 text-xs">→</span>
                  )}
                </div>
              )
            })}
          </div>

          {phase === 'polling' && (
            <p className="mt-3 text-xs text-gray-400">
              Waiting for Replenishment Engine to create a Purchase Order… (checking every 2 s)
            </p>
          )}
          {phase === 'timeout' && (
            <p className="mt-3 text-sm text-amber-600">
              Purchase Order not detected after 24 s — the system may still be processing.{' '}
              <button onClick={onSwitchToApprovals} className="underline hover:text-amber-700">
                Check Approvals tab
              </button>
            </p>
          )}
        </div>
      )}

      {/* Step 3 — PO approval */}
      {(phase === 'found' || phase === 'approving' || phase === 'done') && foundPO && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-1">Step 3 — Review and approve the reorder</h3>
          {phase !== 'done' && (
            <p className="text-xs text-gray-500 mb-4">
              The system flagged this order for manual sign-off because it exceeds the auto-approve
              limit. In practice, routine small orders go straight through — you only see the
              high-value ones. Review the details below, then approve to send directly to the supplier.
            </p>
          )}

          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm mb-5">
            <div>
              <dt className="text-xs text-gray-500">Order ID</dt>
              <dd className="font-mono text-gray-800 text-xs truncate">{foundPO.poId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Status</dt>
              <dd>
                {phase === 'done'
                  ? <span className="inline-block px-2 py-0.5 rounded-full bg-green-100 text-green-700 text-xs font-semibold">APPROVED</span>
                  : <span className="inline-block px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 text-xs font-semibold">AWAITING APPROVAL</span>
                }
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Item</dt>
              <dd className="text-gray-800">
                {SKU_NAMES[foundPO.skuId] ?? foundPO.skuId}
                <span className="text-gray-400 ml-1 text-xs">({foundPO.skuId})</span>
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Warehouse</dt>
              <dd className="text-gray-800">{foundPO.dcId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Units ordered</dt>
              <dd className="text-gray-800">{foundPO.quantity.toLocaleString()} units</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Order value</dt>
              <dd className="text-gray-800">£{foundPO.totalValue.toFixed(2)}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Supplier</dt>
              <dd className="text-gray-800">
                {supplierMap[foundPO.supplierId] ?? foundPO.supplierId}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Raised at</dt>
              <dd className="text-gray-800">{new Date(foundPO.createdAt).toLocaleString()}</dd>
            </div>
          </dl>

          {phase === 'done' ? (
            <div className="bg-green-50 border border-green-200 rounded p-4">
              <p className="text-sm text-green-800 font-semibold mb-1">Order confirmed — supplier has been notified</p>
              <p className="text-sm text-green-700">
                {foundPO.quantity.toLocaleString()} units of {SKU_NAMES[foundPO.skuId] ?? foundPO.skuId} are
                now on order. Estimated arrival: 3–5 business days. Your store will have stock back on
                the shelves before this weekend's gap turns into lost sales.
              </p>
              <p className="text-xs text-green-600 mt-2">
                The order is now visible in the{' '}
                <button
                  onClick={onSwitchToApprovals}
                  className="underline font-medium hover:text-green-800"
                >
                  Supplier Orders tab
                </button>
                . Run the demo again by clicking Reset above.
              </p>
            </div>
          ) : (
            <button
              onClick={handleApprove}
              disabled={phase === 'approving'}
              className="px-4 py-2 bg-green-600 text-white text-sm font-medium rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {phase === 'approving' ? 'Sending to supplier…' : 'Approve — send to supplier'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
