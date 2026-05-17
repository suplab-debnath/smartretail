import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { KpiCard } from '../../components/KpiCard'

describe('KpiCard', () => {
  it('renders label and value', () => {
    render(<KpiCard label="Forecast Accuracy" value="92%" trend="STABLE" color="green" />)
    expect(screen.getByText('Forecast Accuracy')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
  })

  it('shows IMPROVING trend indicator', () => {
    render(<KpiCard label="MAPE" value="8%" trend="IMPROVING" color="green" />)
    expect(screen.getByText('▲ Improving')).toBeInTheDocument()
  })

  it('shows DEGRADING trend indicator', () => {
    render(<KpiCard label="MAPE" value="20%" trend="DEGRADING" color="red" />)
    expect(screen.getByText('▼ Degrading')).toBeInTheDocument()
  })

  it('shows STABLE trend indicator', () => {
    render(<KpiCard label="OTD" value="85%" trend="STABLE" color="neutral" />)
    expect(screen.getByText('— Stable')).toBeInTheDocument()
  })

  it('shows INCREASING direction trend', () => {
    render(<KpiCard label="Stockouts" value="12" trend="INCREASING" color="red" />)
    expect(screen.getByText('▲ Increasing')).toBeInTheDocument()
  })

  it('shows DECREASING direction trend', () => {
    render(<KpiCard label="Stockouts" value="5" trend="DECREASING" color="green" />)
    expect(screen.getByText('▼ Decreasing')).toBeInTheDocument()
  })

  it('renders subtitle when provided', () => {
    render(<KpiCard label="KPI" value="100" trend="STABLE" color="neutral" subtitle="vs last week" />)
    expect(screen.getByText('vs last week')).toBeInTheDocument()
  })

  it('does not render subtitle when omitted', () => {
    render(<KpiCard label="KPI" value="100" trend="STABLE" color="neutral" />)
    expect(screen.queryByText(/vs last week/)).not.toBeInTheDocument()
  })

  it('is interactive when onClick is provided', async () => {
    const onClick = vi.fn()
    render(<KpiCard label="KPI" value="1" trend="STABLE" color="neutral" onClick={onClick} />)
    const card = screen.getByRole('button')
    await userEvent.click(card)
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('shows collapse hint when expanded and interactive', () => {
    render(
      <KpiCard label="KPI" value="1" trend="STABLE" color="neutral"
                onClick={vi.fn()} isExpanded={true} />
    )
    expect(screen.getByText('Click to collapse')).toBeInTheDocument()
  })

  it('shows expand hint when not expanded and interactive', () => {
    render(
      <KpiCard label="KPI" value="1" trend="STABLE" color="neutral"
                onClick={vi.fn()} isExpanded={false} />
    )
    expect(screen.getByText('Click to explore')).toBeInTheDocument()
  })

  it('is not interactive when onClick is absent', () => {
    render(<KpiCard label="KPI" value="1" trend="STABLE" color="neutral" />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
