import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { DeliveryHistogram } from '../../components/DeliveryHistogram'
import type { SupplierPerformanceEntry } from '../../types'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
}))

const makeSupplier = (overrides: Partial<SupplierPerformanceEntry> = {}): SupplierPerformanceEntry => ({
  supplierId: 's1', supplierName: 'Acme Ltd', otdRate: 0.92, fillRate: 0.95,
  earlyCount: 5, onTimeCount: 20, lateCount: 2, openExceptions: 0,
  ...overrides,
})

describe('DeliveryHistogram', () => {
  it('renders the chart heading', () => {
    render(<DeliveryHistogram suppliers={[makeSupplier()]} />)
    expect(screen.getByText('Delivery Performance Distribution')).toBeInTheDocument()
  })

  it('renders subtitle', () => {
    render(<DeliveryHistogram suppliers={[makeSupplier()]} />)
    expect(screen.getByText(/Early \/ On-Time \/ Late/)).toBeInTheDocument()
  })

  it('renders with empty suppliers list', () => {
    render(<DeliveryHistogram suppliers={[]} />)
    expect(screen.getByText('Delivery Performance Distribution')).toBeInTheDocument()
  })

  it('renders with multiple suppliers', () => {
    render(<DeliveryHistogram suppliers={[
      makeSupplier({ supplierId: 's1', supplierName: 'Acme Ltd' }),
      makeSupplier({ supplierId: 's2', supplierName: 'Beta Co' }),
    ]} />)
    expect(screen.getByText('Delivery Performance Distribution')).toBeInTheDocument()
  })
})
