import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { InventoryOverviewTab } from '../../components/InventoryOverviewTab'
import { useInventoryPositions } from '../../hooks/useInventoryPositions'
import type { InventoryPositionListResponse } from '../../types'

vi.mock('../../hooks/useInventoryPositions')
const mockedHook = vi.mocked(useInventoryPositions)

const makePos = (overrides = {}) => ({
  positionId: 'p1', skuId: 'SKU-001', dcId: 'DC-LONDON',
  onHand: 500, inTransit: 100, reserved: 50, reorderPoint: 200, safetyStock: 80, version: 1, lastUpdatedAt: '2026-05-18T00:00:00Z',
  ...overrides,
})

const mockData: InventoryPositionListResponse = { positions: [makePos()], dataFreshness: '2026-05-18T00:00:00Z' }

beforeEach(() => vi.clearAllMocks())

describe('InventoryOverviewTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('Loading inventory positions…')).toBeInTheDocument()
  })

  it('shows error state', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: 'HTTP 500' })
    render(<InventoryOverviewTab />)
    expect(screen.getByText(/Error loading positions: HTTP 500/)).toBeInTheDocument()
  })

  it('shows empty state when no positions', () => {
    mockedHook.mockReturnValue({ data: { positions: [], dataFreshness: '' }, loading: false, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('No inventory positions found')).toBeInTheDocument()
  })

  it('renders position SKU', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
  })

  it('shows OK status when ATP > reorderPoint', () => {
    // onHand=500, inTransit=100, reserved=50 → ATP=550, reorderPoint=200 → OK
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('shows REORDER SOON when 0 < ATP < reorderPoint', () => {
    const pos = makePos({ onHand: 150, inTransit: 0, reserved: 0, reorderPoint: 200 }) // ATP=150 < 200
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('REORDER SOON')).toBeInTheDocument()
  })

  it('shows CRITICAL when ATP <= 0', () => {
    const pos = makePos({ onHand: 30, inTransit: 0, reserved: 50, reorderPoint: 200 }) // ATP=-20
    mockedHook.mockReturnValue({ data: { positions: [pos], dataFreshness: '' }, loading: false, error: null })
    render(<InventoryOverviewTab />)
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  })

  it('changes DC filter via select', async () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null })
    render(<InventoryOverviewTab />)
    const select = screen.getByRole('combobox')
    await userEvent.selectOptions(select, 'DC-MANCHESTER')
    expect(mockedHook).toHaveBeenCalledWith('DC-MANCHESTER')
  })
})
