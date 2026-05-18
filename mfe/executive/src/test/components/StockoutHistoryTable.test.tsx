import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { StockoutHistoryTable } from '../../components/StockoutHistoryTable'
import type { StockoutAlertDataPoint } from '../../types'

const history: StockoutAlertDataPoint[] = [
  { alertDate: '2026-05-18', criticalCount: 5 },
  { alertDate: '2026-05-17', criticalCount: 0 },
]

describe('StockoutHistoryTable', () => {
  it('renders section heading', () => {
    render(<StockoutHistoryTable history={[]} />)
    expect(screen.getByText('Stockout Alert History (Last 30 Days)')).toBeInTheDocument()
  })

  it('renders table headers', () => {
    render(<StockoutHistoryTable history={[]} />)
    expect(screen.getByText('Date')).toBeInTheDocument()
    expect(screen.getByText('CRITICAL Alerts')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
  })

  it('renders rows for each history point', () => {
    render(<StockoutHistoryTable history={history} />)
    expect(screen.getByText('2026-05-18')).toBeInTheDocument()
    expect(screen.getByText('2026-05-17')).toBeInTheDocument()
  })

  it('shows CRITICAL badge when criticalCount > 0', () => {
    render(<StockoutHistoryTable history={[{ alertDate: '2026-05-18', criticalCount: 3 }]} />)
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  })

  it('shows CLEAR badge when criticalCount is 0', () => {
    render(<StockoutHistoryTable history={[{ alertDate: '2026-05-18', criticalCount: 0 }]} />)
    expect(screen.getByText('CLEAR')).toBeInTheDocument()
  })

  it('renders critical count value', () => {
    render(<StockoutHistoryTable history={[{ alertDate: '2026-05-18', criticalCount: 5 }]} />)
    expect(screen.getByText('5')).toBeInTheDocument()
  })
})
