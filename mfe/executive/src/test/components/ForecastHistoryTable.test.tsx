import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { ForecastHistoryTable } from '../../components/ForecastHistoryTable'
import type { MapeDataPoint } from '../../types'

const makeHistory = (count = 3): MapeDataPoint[] =>
  Array.from({ length: count }, (_, i) => ({
    runDate: `2026-05-${String(18 - i).padStart(2, '0')}`,
    mape: 0.08 + i * 0.02,
  }))

describe('ForecastHistoryTable', () => {
  it('renders table headers', () => {
    render(<ForecastHistoryTable history={[]} />)
    expect(screen.getByText('Date')).toBeInTheDocument()
    expect(screen.getByText('MAPE')).toBeInTheDocument()
    expect(screen.getByText('Accuracy')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
  })

  it('renders a row for each history point', () => {
    render(<ForecastHistoryTable history={makeHistory(3)} />)
    expect(screen.getByText('2026-05-18')).toBeInTheDocument()
    expect(screen.getByText('2026-05-17')).toBeInTheDocument()
    expect(screen.getByText('2026-05-16')).toBeInTheDocument()
  })

  it('shows Within threshold for mape < 0.15', () => {
    render(<ForecastHistoryTable history={[{ runDate: '2026-05-18', mape: 0.08 }]} />)
    expect(screen.getByText('Within threshold')).toBeInTheDocument()
  })

  it('shows Threshold breached for mape >= 0.15', () => {
    render(<ForecastHistoryTable history={[{ runDate: '2026-05-18', mape: 0.20 }]} />)
    expect(screen.getByText('Threshold breached')).toBeInTheDocument()
  })

  it('renders MAPE and accuracy percentages', () => {
    render(<ForecastHistoryTable history={[{ runDate: '2026-05-18', mape: 0.08 }]} />)
    expect(screen.getByText('8.00%')).toBeInTheDocument()
    expect(screen.getByText('92.0%')).toBeInTheDocument()
  })

  it('renders at most 10 rows even with more than 10 history points', () => {
    render(<ForecastHistoryTable history={makeHistory(15)} />)
    const rows = screen.getAllByRole('row')
    // 1 header row + 10 data rows = 11
    expect(rows.length).toBe(11)
  })

  it('renders the section heading', () => {
    render(<ForecastHistoryTable history={[]} />)
    expect(screen.getByText('Forecast Accuracy History')).toBeInTheDocument()
  })
})
