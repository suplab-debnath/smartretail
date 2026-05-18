import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { StockoutChart, formatDate } from '../../components/StockoutChart'
import type { StockoutAlertDataPoint } from '../../types'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null,
}))

const history: StockoutAlertDataPoint[] = [
  { alertDate: '2026-05-18', criticalCount: 3 },
  { alertDate: '2026-05-17', criticalCount: 0 },
]

describe('StockoutChart', () => {
  it('renders the chart heading', () => {
    render(<StockoutChart history={history} />)
    expect(screen.getByText(/Stockout Frequency/)).toBeInTheDocument()
  })

  it('renders with empty history', () => {
    render(<StockoutChart history={[]} />)
    expect(screen.getByText(/Stockout Frequency/)).toBeInTheDocument()
  })
})

describe('formatDate', () => {
  it('slices to MM-DD', () => expect(formatDate('2026-05-18')).toBe('05-18'))
})
