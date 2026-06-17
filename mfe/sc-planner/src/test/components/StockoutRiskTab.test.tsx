import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { StockoutRiskTab } from '../../components/StockoutRiskTab'
import { useInventoryPositions } from '../../hooks/useInventoryPositions'
import type { FetchError } from '@smartretail/auth'

vi.mock('@smartretail/auth', () => ({
  ErrorBanner: ({ error }: { error: FetchError | null }) =>
    error ? <div data-testid="error-banner">Error: {error.message}</div> : null,
  Tooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('../../hooks/useInventoryPositions')
const mockedHook = vi.mocked(useInventoryPositions)

const makePos = (overrides = {}) => ({
  positionId: 'p1', skuId: 'SKU-001', dcId: 'DC-LONDON',
  onHand: 50, inTransit: 0, reserved: 0, reorderPoint: 200, safetyStock: 80, version: 1, lastUpdatedAt: '2026-05-18T00:00:00Z',
  ...overrides,
})

const serverError: FetchError = { kind: 'server', status: 500, message: 'HTTP 500' }

beforeEach(() => vi.clearAllMocks())

describe('StockoutRiskTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('Loading inventory positions…')).toBeInTheDocument()
  })

  it('shows error banner', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: serverError, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByTestId('error-banner')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
  })

  it('shows empty state when no at-risk positions', () => {
    const okPos = makePos({ onHand: 500, reorderPoint: 100 }) // ATP=500 > 100 → OK (filtered out by default)
    mockedHook.mockReturnValue({ data: { positions: [okPos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('No at-risk positions')).toBeInTheDocument()
  })

  it('shows CRITICAL risk row', () => {
    const pos = makePos({ onHand: 0, inTransit: 0, reserved: 0 }) // ATP=0 → CRITICAL
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  })

  it('shows HIGH risk row', () => {
    // ATP < reorderPoint * 0.5: ATP=80, reorderPoint=200 → 80 < 100 → HIGH
    const pos = makePos({ onHand: 80, reorderPoint: 200 })
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('HIGH')).toBeInTheDocument()
  })

  it('shows MODERATE risk row', () => {
    // ATP=150, reorderPoint=200 → 150 < 200 but >= 100 → MODERATE
    const pos = makePos({ onHand: 150, reorderPoint: 200 })
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('MODERATE')).toBeInTheDocument()
  })

  it('calls onTriggerReplenishment when Trigger button clicked', async () => {
    const trigger = vi.fn()
    const pos = makePos({ onHand: 0 }) // CRITICAL
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={trigger} />)
    await userEvent.click(screen.getByRole('button', { name: 'Create PO' }))
    expect(trigger).toHaveBeenCalledWith('SKU-001', 'DC-LONDON')
  })

  it('shows OK rows when Show OK checkbox is checked', async () => {
    const okPos  = makePos({ positionId: 'p1', skuId: 'SKU-OK',   onHand: 500, reorderPoint: 100 })
    const critPos = makePos({ positionId: 'p2', skuId: 'SKU-CRIT', onHand: 0,   reorderPoint: 200 })
    mockedHook.mockReturnValue({ data: { positions: [okPos, critPos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.queryByText('SKU-OK')).not.toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Show OK rows'))
    expect(screen.getByText('SKU-OK')).toBeInTheDocument()
  })

  it('bulk triggers selected CRITICAL/HIGH items', async () => {
    const trigger = vi.fn()
    const pos = makePos({ onHand: 0 }) // CRITICAL, actionable
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<StockoutRiskTab onTriggerReplenishment={trigger} />)
    const checkbox = screen.getByRole('checkbox', { name: '' })
    await userEvent.click(checkbox)
    await userEvent.click(screen.getByRole('button', { name: /Raise POs/ }))
    expect(trigger).toHaveBeenCalledWith('SKU-001', 'DC-LONDON')
  })
})
