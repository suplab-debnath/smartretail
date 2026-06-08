import { useState } from 'react'
import { getApiBase } from '@smartretail/auth'

interface Props {
  skuId: string
  dcId: string
  onClose: () => void
  onSuccess: (poId: string) => void
}

export function ReplenishmentTriggerModal({ skuId, dcId, onClose, onSuccess }: Props) {
  const [quantity, setQuantity] = useState<number>(1)
  const [notes, setNotes] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [inlineError, setInlineError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (quantity < 1) return
    setSubmitting(true)
    setInlineError(null)

    try {
      const res = await fetch(`${getApiBase()}/v1/replenishment/orders`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Dev-Role': 'SC_PLANNER',
        },
        body: JSON.stringify({ skuId, dcId, quantity, notes: notes || undefined }),
      })

      if (res.status === 201) {
        const body = await res.json() as { poId: string }
        onSuccess(body.poId)
        onClose()
      } else if (res.status === 409) {
        setInlineError('A PENDING order already exists for this SKU/DC')
      } else {
        setInlineError(`Request failed: HTTP ${res.status}`)
      }
    } catch (e) {
      setInlineError(e instanceof Error ? e.message : 'Unknown error')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md p-6">
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Trigger Replenishment</h2>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">SKU</label>
            <input
              type="text"
              value={skuId}
              readOnly
              className="w-full border border-gray-200 rounded px-3 py-2 bg-gray-50 text-sm font-mono text-gray-500"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">DC</label>
            <input
              type="text"
              value={dcId}
              readOnly
              className="w-full border border-gray-200 rounded px-3 py-2 bg-gray-50 text-sm text-gray-500"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Quantity <span className="text-red-500">*</span>
            </label>
            <input
              type="number"
              value={quantity}
              onChange={e => setQuantity(Number(e.target.value))}
              min={1}
              required
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Notes (optional)</label>
            <textarea
              value={notes}
              onChange={e => setNotes(e.target.value)}
              rows={3}
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm resize-none"
              placeholder="Additional context…"
            />
          </div>

          {inlineError && (
            <p className="text-sm text-red-600">{inlineError}</p>
          )}

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-700 border border-gray-300 rounded hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || quantity < 1}
              className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
            >
              {submitting ? 'Submitting…' : 'Submit Order'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
