import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { CycleTimeHistoryTable } from '../../components/CycleTimeHistoryTable'
import type { CycleTimeDataPoint } from '../../types'

const history: CycleTimeDataPoint[] = [
  { weekStart: '2026-05-12', averageDays: 3.5, poCount: 10 },
  { weekStart: '2026-05-05', averageDays: 5.0, poCount: 8 },
  { weekStart: '2026-04-28', averageDays: 8.0, poCount: 6 },
]

describe('CycleTimeHistoryTable', () => {
  it('renders section heading', () => {
    render(<CycleTimeHistoryTable history={[]} />)
    expect(screen.getByText('Cycle Time History (Last 90 Days)')).toBeInTheDocument()
  })

  it('renders table headers', () => {
    render(<CycleTimeHistoryTable history={[]} />)
    expect(screen.getByText('Week Starting')).toBeInTheDocument()
    expect(screen.getByText('Avg Days')).toBeInTheDocument()
    expect(screen.getByText('PO Count')).toBeInTheDocument()
  })

  it('renders rows for each history point', () => {
    render(<CycleTimeHistoryTable history={history} />)
    expect(screen.getByText('2026-05-12')).toBeInTheDocument()
    expect(screen.getByText('2026-05-05')).toBeInTheDocument()
    expect(screen.getByText('2026-04-28')).toBeInTheDocument()
  })

  it('shows green color for avgDays < 4', () => {
    render(<CycleTimeHistoryTable history={[{ weekStart: '2026-05-12', averageDays: 3.5, poCount: 5 }]} />)
    const cell = screen.getByText('3.5d')
    expect(cell.className).toContain('text-green-700')
  })

  it('shows amber color for 4 <= avgDays <= 7', () => {
    render(<CycleTimeHistoryTable history={[{ weekStart: '2026-05-12', averageDays: 5.0, poCount: 5 }]} />)
    const cell = screen.getByText('5.0d')
    expect(cell.className).toContain('text-amber-700')
  })

  it('shows red color for avgDays > 7', () => {
    render(<CycleTimeHistoryTable history={[{ weekStart: '2026-05-12', averageDays: 8.0, poCount: 5 }]} />)
    const cell = screen.getByText('8.0d')
    expect(cell.className).toContain('text-red-700')
  })

  it('renders PO count', () => {
    render(<CycleTimeHistoryTable history={[{ weekStart: '2026-05-12', averageDays: 3.5, poCount: 12 }]} />)
    expect(screen.getByText('12')).toBeInTheDocument()
  })
})
