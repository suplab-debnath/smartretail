import { useState, useRef, useEffect, useCallback } from 'react'
import type { PurchaseOrder, PurchaseOrderListResponse } from '../types'

interface FormValues {
  storeId: string
  skuId: string
  dcId: string
  quantity: string
  unitPrice: string
}

type DemoPhase = 'idle' | 'injecting' | 'polling' | 'found' | 'approving' | 'done' | 'timeout'

const PIPELINE_STEPS = ['Sale accepted', 'Inventory updated', 'Stock alert raised', 'PO created']

const DC_OPTIONS = [
  { value: 'DC-LONDON',     label: 'London DC'     },
  { value: 'DC-MANCHESTER', label: 'Manchester DC' },
  { value: 'DC-BIRMINGHAM', label: 'Birmingham DC' },
]

interface Props {
  onSwitchToApprovals: () => void
}

export function DemoTab({ onSwitchToApprovals }: Props) {
  const [form, setForm] = useState<FormValues>({
    storeId:  'STORE-001',
    skuId:    'SKU-BEV-001',
    dcId:     'DC-LONDON',
    quantity: '30',
    unitPrice: '8.50',
  })
  const [phase, setPhase]               = useState<DemoPhase>('idle')
  const [completedSteps, setCompleted]  = useState(0)
  const [foundPO, setFoundPO]           = useState<PurchaseOrder | null>(null)
  const [error, setError]               = useState<string | null>(null)

  const pollRef     = useRef<ReturnType<typeof setInterval> | null>(null)
  const snapshotRef = useRef<Set<string>>(new Set())
  const attemptRef  = useRef(0)

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current) }, [])

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
    if (attemptRef.current > 7) {
      stopPolling()
      setPhase('timeout')
      return
    }
    try {
      const res = await fetch('/v1/replenishment/orders?status=PENDING_APPROVAL&size=20', {
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
      }
    } catch {
      // keep polling
    }
  }, [])

  async function handleInject() {
    setError(null)
    setPhase('injecting')
    setCompleted(0)

    try {
      const snap = await fetch('/v1/replenishment/orders?status=PENDING_APPROVAL&size=100', {
        headers: { 'X-Dev-Role': 'SC_PLANNER' },
      })
      if (snap.ok) {
        const data: PurchaseOrderListResponse = await snap.json()
        snapshotRef.current = new Set(data.orders.map(o => o.poId))
      }
    } catch { /* proceed without snapshot */ }

    try {
      const res = await fetch('/v1/ingest/events', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Dev-Role': 'SC_PLANNER' },
        body: JSON.stringify({
          transactionId:  crypto.randomUUID(),
          storeId:        form.storeId,
          skuId:          form.skuId,
          dcId:           form.dcId,
          quantity:       parseInt(form.quantity, 10),
          unitPrice:      parseFloat(form.unitPrice),
          channel:        'POS',
          eventTimestamp: new Date().toISOString(),
        }),
      })
      if (!res.ok) {
        const text = await res.text()
        setError(`SIS rejected event: HTTP ${res.status} — ${text}`)
        setPhase('idle')
        return
      }
    } catch (e) {
      setError(`Network error: ${e instanceof Error ? e.message : 'Unknown'}`)
      setPhase('idle')
      return
    }

    setCompleted(1)   // sale accepted
    setPhase('polling')
    attemptRef.current = 0

    setTimeout(() => setCompleted(prev => Math.max(prev, 2)), 2000)  // inventory updated
    setTimeout(() => setCompleted(prev => Math.max(prev, 3)), 4000)  // alert raised

    pollRef.current = setInterval(checkForNewPO, 2000)
  }

  async function handleApprove() {
    if (!foundPO) return
    setPhase('approving')
    try {
      const res = await fetch(`/v1/replenishment/orders/${foundPO.poId}/approve`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Dev-Role': 'SC_PLANNER',
          'X-Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ version: foundPO.version }),
      })
      if (res.ok) {
        setPhase('done')
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

  return (
    <div className="space-y-6 max-w-2xl mx-auto">
      <div>
        <h2 className="text-xl font-semibold text-gray-900">End-to-End Demo</h2>
        <p className="mt-1 text-sm text-gray-500">
          Simulate a POS sale that triggers the automated replenishment flow: sale → inventory
          update → stock alert → PO creation → approval.
        </p>
      </div>

      {/* Step 1 — trigger card */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">1 — Simulate POS Sale</h3>
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
            <span className="text-xs text-gray-500">SKU</span>
            <input
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.skuId}
              onChange={e => setForm(f => ({ ...f, skuId: e.target.value }))}
              disabled={phase !== 'idle'}
            />
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Distribution Centre</span>
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
            <span className="text-xs text-gray-500">Quantity sold</span>
            <input
              type="number" min="1"
              className="mt-1 block w-full border border-gray-300 rounded px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50"
              value={form.quantity}
              onChange={e => setForm(f => ({ ...f, quantity: e.target.value }))}
              disabled={phase !== 'idle'}
            />
          </label>
          <label className="block">
            <span className="text-xs text-gray-500">Unit price (£)</span>
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
            {phase === 'injecting' ? 'Sending…' : 'Simulate POS Sale'}
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
          <h3 className="text-sm font-semibold text-gray-700 mb-4">2 — Replenishment Pipeline</h3>
          <div className="flex flex-wrap items-center gap-2">
            {PIPELINE_STEPS.map((label, i) => {
              const done   = completedSteps > i
              const active = completedSteps === i && phase === 'polling'
              return (
                <div key={label} className="flex items-center gap-2">
                  <span className={[
                    'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium',
                    done   ? 'bg-green-100 text-green-700' :
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
              Waiting for replenishment engine to create PO…
            </p>
          )}
          {phase === 'timeout' && (
            <p className="mt-3 text-sm text-amber-600">
              PO not detected after 15 s — the system may still be processing.{' '}
              <button onClick={onSwitchToApprovals} className="underline hover:text-amber-700">
                Check Approvals tab
              </button>
            </p>
          )}
        </div>
      )}

      {/* Step 3 — PO card */}
      {(phase === 'found' || phase === 'approving' || phase === 'done') && foundPO && (
        <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-4">3 — Purchase Order</h3>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm mb-5">
            <div>
              <dt className="text-xs text-gray-500">PO ID</dt>
              <dd className="font-mono text-gray-800 text-xs truncate">{foundPO.poId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Status</dt>
              <dd>
                {phase === 'done'
                  ? <span className="inline-block px-2 py-0.5 rounded-full bg-green-100 text-green-700 text-xs font-semibold">APPROVED</span>
                  : <span className="inline-block px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 text-xs font-semibold">PENDING APPROVAL</span>
                }
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">SKU</dt>
              <dd className="text-gray-800">{foundPO.skuId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">DC</dt>
              <dd className="text-gray-800">{foundPO.dcId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Quantity</dt>
              <dd className="text-gray-800">{foundPO.quantity.toLocaleString()} units</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Total value</dt>
              <dd className="text-gray-800">£{foundPO.totalValue.toFixed(2)}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Supplier</dt>
              <dd className="text-gray-800">{foundPO.supplierId}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Created</dt>
              <dd className="text-gray-800">{new Date(foundPO.createdAt).toLocaleString()}</dd>
            </div>
          </dl>

          {phase === 'done' ? (
            <p className="text-sm text-green-700 font-medium">
              Flow complete — order sent to supplier.
            </p>
          ) : (
            <button
              onClick={handleApprove}
              disabled={phase === 'approving'}
              className="px-4 py-2 bg-green-600 text-white text-sm font-medium rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {phase === 'approving' ? 'Approving…' : 'Approve this PO'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
