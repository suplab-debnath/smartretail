import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { CycleTimeChart, formatWeek, formatCycleTimeTooltip } from '../../components/CycleTimeChart'
import type { CycleTimeDataPoint } from '../../types'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Line: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null,
  ReferenceLine: () => null,
}))

const history: CycleTimeDataPoint[] = [
  { weekStart: '2026-05-12', averageDays: 4.2, poCount: 10 },
  { weekStart: '2026-05-05', averageDays: 3.8, poCount: 8 },
]

describe('CycleTimeChart', () => {
  it('renders the chart heading', () => {
    render(<CycleTimeChart history={history} overallAverage={4.0} />)
    expect(screen.getByText(/Replenishment Cycle Time/)).toBeInTheDocument()
  })

  it('renders with empty history', () => {
    render(<CycleTimeChart history={[]} overallAverage={0} />)
    expect(screen.getByText(/Replenishment Cycle Time/)).toBeInTheDocument()
  })
})

describe('formatWeek', () => {
  it('slices to MM-DD', () => expect(formatWeek('2026-05-12')).toBe('05-12'))
})

describe('formatCycleTimeTooltip', () => {
  it('formats avgDays label', () => {
    expect(formatCycleTimeTooltip(4.2, 'avgDays')).toEqual(['4.2d', 'Avg cycle time'])
  })

  it('passes through for other names', () => {
    expect(formatCycleTimeTooltip(10, 'poCount')).toEqual([10, 'poCount'])
  })
})
