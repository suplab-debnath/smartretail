import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ExceptionQueueTab } from '../../components/ExceptionQueueTab'
import { useExceptionQueue } from '../../hooks/useExceptionQueue'
import type { StockAlertListResponse } from '../../types'

vi.mock('../../hooks/useExceptionQueue')
const mockedHook = vi.mocked(useExceptionQueue)

const makeAlert = (overrides = {}) => ({
  alertId: 'a1', positionId: 'p1', skuId: 'SKU-001', dcId: 'DC-LONDON',
  alertType: 'LOW_STOCK' as const, severity: 'HIGH' as const, status: 'ACTIVE' as const,
  actualValue: 30, thresholdValue: 100, raisedAt: '2026-05-18T00:00:00Z',
  ...overrides,
})

const mockData: StockAlertListResponse = { alerts: [makeAlert()], dataFreshness: '2026-05-18T00:00:00Z' }

beforeEach(() => vi.clearAllMocks())

describe('ExceptionQueueTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('Loading exception queue…')).toBeInTheDocument()
  })

  it('shows error state', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: 'HTTP 503', refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText(/Error loading alerts: HTTP 503/)).toBeInTheDocument()
  })

  it('shows empty state when no alerts', () => {
    mockedHook.mockReturnValue({ data: { alerts: [], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('No active exceptions')).toBeInTheDocument()
  })

  it('renders alert data in table', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
    expect(screen.getAllByText('DC-LONDON').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('LOW STOCK')).toBeInTheDocument()
  })

  it('renders OVERSTOCK chip for OVERSTOCK type', () => {
    mockedHook.mockReturnValue({ data: { alerts: [makeAlert({ alertType: 'OVERSTOCK' })], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    expect(screen.getByText('OVERSTOCK')).toBeInTheDocument()
  })

  it('calls onTriggerReplenishment with skuId and dcId', async () => {
    const trigger = vi.fn()
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={trigger} />)
    await userEvent.click(screen.getByRole('button', { name: 'Trigger Replenishment' }))
    expect(trigger).toHaveBeenCalledWith('SKU-001', 'DC-LONDON')
  })

  it('filters alerts by DC', async () => {
    const london = makeAlert({ alertId: 'a1', skuId: 'SKU-001', dcId: 'DC-LONDON' })
    const manch  = makeAlert({ alertId: 'a2', skuId: 'SKU-002', dcId: 'DC-MANCHESTER' })
    mockedHook.mockReturnValue({ data: { alerts: [london, manch], dataFreshness: '' }, loading: false, error: null, refetch: vi.fn() })
    render(<ExceptionQueueTab onTriggerReplenishment={vi.fn()} />)
    await userEvent.selectOptions(screen.getByRole('combobox'), 'DC-LONDON')
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
    expect(screen.queryByText('SKU-002')).not.toBeInTheDocument()
  })
})
