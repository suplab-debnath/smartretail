import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { DataFreshnessIndicator } from '../../components/DataFreshnessIndicator'

describe('DataFreshnessIndicator', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows seconds when freshness is recent', () => {
    const now = new Date(Date.now() - 10_000).toISOString()
    render(<DataFreshnessIndicator dataFreshness={now} onRefresh={vi.fn()} />)
    expect(screen.getByText(/10s ago/)).toBeInTheDocument()
  })

  it('shows minutes when freshness is older than 60s', () => {
    const twoMinutesAgo = new Date(Date.now() - 120_000).toISOString()
    render(<DataFreshnessIndicator dataFreshness={twoMinutesAgo} onRefresh={vi.fn()} />)
    expect(screen.getByText(/2m ago/)).toBeInTheDocument()
  })

  it('calls onRefresh when Refresh button is clicked', () => {
    const onRefresh = vi.fn()
    render(<DataFreshnessIndicator dataFreshness={new Date().toISOString()} onRefresh={onRefresh} />)
    fireEvent.click(screen.getByText('Refresh'))
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it('renders a Refresh button', () => {
    render(<DataFreshnessIndicator dataFreshness={new Date().toISOString()} onRefresh={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument()
  })
})
