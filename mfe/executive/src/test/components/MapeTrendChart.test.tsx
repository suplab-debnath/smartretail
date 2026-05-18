import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { MapeTrendChart, formatPercent, formatDate, tooltipFormatter } from '../../components/MapeTrendChart'
import type { MapeDataPoint } from '../../types'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Line: () => null, XAxis: () => null, YAxis: () => null,
  CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
  ReferenceLine: () => null,
}))

const makeHistory = (): MapeDataPoint[] => [
  { runDate: '2026-05-18', mape: 0.12 },
  { runDate: '2026-05-17', mape: 0.10 },
]

describe('MapeTrendChart', () => {
  it('renders the chart heading', () => {
    render(<MapeTrendChart history={makeHistory()} />)
    expect(screen.getByText('MAPE Trend — Last 30 Forecast Runs')).toBeInTheDocument()
  })

  it('renders with empty history', () => {
    render(<MapeTrendChart history={[]} />)
    expect(screen.getByText('MAPE Trend — Last 30 Forecast Runs')).toBeInTheDocument()
  })
})

describe('formatPercent', () => {
  it('formats 0.08 as 8.0%', () => expect(formatPercent(0.08)).toBe('8.0%'))
  it('formats 0.15 as 15.0%', () => expect(formatPercent(0.15)).toBe('15.0%'))
})

describe('formatDate', () => {
  it('slices to MM-DD', () => expect(formatDate('2026-05-18')).toBe('05-18'))
})

describe('tooltipFormatter', () => {
  it('returns formatted string for mape name within threshold', () => {
    const [label, name] = tooltipFormatter(0.08, 'mape') as [string, string]
    expect(label).toContain('8.0% MAPE')
    expect(label).toContain('Within threshold')
    expect(name).toBe('MAPE')
  })

  it('returns formatted string for mape name above threshold', () => {
    const [label] = tooltipFormatter(0.20, 'mape') as [string, string]
    expect(label).toContain('Threshold breached')
  })

  it('passes through for non-mape names', () => {
    const result = tooltipFormatter(42, 'other')
    expect(result).toEqual([42, 'other'])
  })
})
