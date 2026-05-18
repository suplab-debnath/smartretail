import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { AlertList } from '../../components/AlertList'
import type { StockAlertSummary } from '../../types'

const makeAlert = (overrides: Partial<StockAlertSummary> = {}): StockAlertSummary => ({
  alertId: 'a1',
  skuId: 'SKU-001',
  dcId: 'DC-LONDON',
  alertType: 'LOW_STOCK',
  severity: 'HIGH',
  onHand: 50,
  reorderPoint: 100,
  raisedAt: '2026-05-18T10:00:00Z',
  ...overrides,
})

describe('AlertList', () => {
  it('shows empty state when no alerts', () => {
    render(<AlertList alerts={[]} page={0} totalPages={0} onPageChange={vi.fn()} />)
    expect(screen.getByText('No active alerts for this distribution centre')).toBeInTheDocument()
  })

  it('renders table with alert data', () => {
    const alert = makeAlert({ skuId: 'SKU-042', dcId: 'DC-MANCHESTER', alertType: 'LOW_STOCK', onHand: 30, reorderPoint: 80 })
    render(<AlertList alerts={[alert]} page={0} totalPages={1} onPageChange={vi.fn()} />)
    expect(screen.getByText('SKU-042')).toBeInTheDocument()
    expect(screen.getByText('DC-MANCHESTER')).toBeInTheDocument()
    expect(screen.getByText('LOW STOCK')).toBeInTheDocument()
    expect(screen.getByText('30')).toBeInTheDocument()
    expect(screen.getByText('80')).toBeInTheDocument()
  })

  it('renders column headers', () => {
    render(<AlertList alerts={[makeAlert()]} page={0} totalPages={1} onPageChange={vi.fn()} />)
    for (const header of ['SKU', 'DC', 'Type', 'Severity', 'On Hand', 'Reorder Point', 'Raised At']) {
      expect(screen.getByText(header)).toBeInTheDocument()
    }
  })

  it('hides pagination when totalPages is 1', () => {
    render(<AlertList alerts={[makeAlert()]} page={0} totalPages={1} onPageChange={vi.fn()} />)
    expect(screen.queryByText(/Page \d+ of \d+/)).not.toBeInTheDocument()
  })

  it('shows pagination when totalPages > 1', () => {
    render(<AlertList alerts={[makeAlert()]} page={0} totalPages={3} onPageChange={vi.fn()} />)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
  })

  it('disables Previous button on first page', () => {
    render(<AlertList alerts={[makeAlert()]} page={0} totalPages={3} onPageChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()
  })

  it('disables Next button on last page', () => {
    render(<AlertList alerts={[makeAlert()]} page={2} totalPages={3} onPageChange={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled()
  })

  it('calls onPageChange with next page when Next clicked', async () => {
    const onPageChange = vi.fn()
    render(<AlertList alerts={[makeAlert()]} page={1} totalPages={3} onPageChange={onPageChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(onPageChange).toHaveBeenCalledWith(2)
  })

  it('calls onPageChange with previous page when Previous clicked', async () => {
    const onPageChange = vi.fn()
    render(<AlertList alerts={[makeAlert()]} page={2} totalPages={3} onPageChange={onPageChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(onPageChange).toHaveBeenCalledWith(1)
  })

  it('renders multiple alerts', () => {
    const alerts = [
      makeAlert({ alertId: 'a1', skuId: 'SKU-001' }),
      makeAlert({ alertId: 'a2', skuId: 'SKU-002' }),
    ]
    render(<AlertList alerts={alerts} page={0} totalPages={1} onPageChange={vi.fn()} />)
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
    expect(screen.getByText('SKU-002')).toBeInTheDocument()
  })
})
