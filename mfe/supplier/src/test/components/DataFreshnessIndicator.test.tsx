import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { DataFreshnessIndicator } from '../../components/DataFreshnessIndicator'

afterEach(() => {
  vi.useRealTimers()
})

describe('DataFreshnessIndicator', () => {
  it('shows seconds ago when under a minute', () => {
    vi.useFakeTimers()
    const now = new Date('2026-05-18T12:00:00Z')
    vi.setSystemTime(now)
    const freshness = new Date(now.getTime() - 30_000).toISOString()
    render(<DataFreshnessIndicator dataFreshness={freshness} onRefresh={vi.fn()} />)
    expect(screen.getByText(/30s ago/)).toBeInTheDocument()
  })

  it('shows minutes ago when over a minute', () => {
    vi.useFakeTimers()
    const now = new Date('2026-05-18T12:00:00Z')
    vi.setSystemTime(now)
    const freshness = new Date(now.getTime() - 120_000).toISOString()
    render(<DataFreshnessIndicator dataFreshness={freshness} onRefresh={vi.fn()} />)
    expect(screen.getByText(/2m ago/)).toBeInTheDocument()
  })

  it('renders a Refresh button', () => {
    render(
      <DataFreshnessIndicator
        dataFreshness={new Date().toISOString()}
        onRefresh={vi.fn()}
      />
    )
    expect(screen.getByRole('button', { name: /refresh/i })).toBeInTheDocument()
  })

  it('calls onRefresh when Refresh button is clicked', async () => {
    // Use real timers for click interaction to avoid userEvent timeout
    const onRefresh = vi.fn()
    render(
      <DataFreshnessIndicator
        dataFreshness={new Date().toISOString()}
        onRefresh={onRefresh}
      />
    )
    await userEvent.click(screen.getByRole('button', { name: /refresh/i }))
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it('updates the label after the tick interval', () => {
    vi.useFakeTimers()
    const now = new Date('2026-05-18T12:00:00Z')
    vi.setSystemTime(now)
    const freshness = new Date(now.getTime() - 10_000).toISOString()
    render(<DataFreshnessIndicator dataFreshness={freshness} onRefresh={vi.fn()} />)
    expect(screen.getByText(/10s ago/)).toBeInTheDocument()
    // advance 5 s (one tick interval) and flush React state updates
    act(() => { vi.advanceTimersByTime(5_000) })
    expect(screen.getByText(/15s ago/)).toBeInTheDocument()
  })
})
