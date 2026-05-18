import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ApprovalWorkflowsTab } from '../../components/ApprovalWorkflowsTab'
import { usePendingApprovals } from '../../hooks/usePendingApprovals'
import type { PurchaseOrder } from '../../types'

vi.mock('../../hooks/usePendingApprovals')
const mockedHook = vi.mocked(usePendingApprovals)

const makePo = (overrides = {}): PurchaseOrder => ({
  poId: 'po-00000000-0001', supplierId: 'sup-00000000-0001', skuId: 'SKU-001', dcId: 'DC-LONDON',
  quantity: 500, totalValue: 2500, workflowStatus: 'PENDING_APPROVAL', version: 3, createdAt: '2026-05-18T00:00:00Z',
  ...overrides,
})

const noop = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('fetch', vi.fn())
  vi.stubGlobal('crypto', { randomUUID: () => 'test-uuid-1234' })
})

describe('ApprovalWorkflowsTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ orders: [], loading: true, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    expect(screen.getByText('Loading pending approvals…')).toBeInTheDocument()
  })

  it('shows error state', () => {
    mockedHook.mockReturnValue({ orders: [], loading: false, error: 'HTTP 503', removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    expect(screen.getByText(/Error: HTTP 503/)).toBeInTheDocument()
  })

  it('shows empty state when no orders', () => {
    mockedHook.mockReturnValue({ orders: [], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    expect(screen.getByText('No POs awaiting approval')).toBeInTheDocument()
  })

  it('renders PO data with truncated IDs', () => {
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    expect(screen.getByText('SKU-001')).toBeInTheDocument()
    expect(screen.getByText('DC-LONDON')).toBeInTheDocument()
  })

  it('calls approve endpoint and shows success toast on 200', async () => {
    const removeOrder = vi.fn()
    vi.mocked(fetch).mockResolvedValue({ ok: true, status: 200 } as Response)
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(removeOrder).toHaveBeenCalledWith('po-00000000-0001'))
    expect(screen.getByText(/approved/)).toBeInTheDocument()
  })

  it('shows warning toast on 409 approve response', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 409 } as Response)
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(screen.getByText(/PO status changed/)).toBeInTheDocument())
  })

  it('shows error toast on approve non-ok response', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 500 } as Response)
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(screen.getByText(/Approve failed: HTTP 500/)).toBeInTheDocument())
  })

  it('shows error toast on approve network error', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('offline'))
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(screen.getByText(/Network error: offline/)).toBeInTheDocument())
  })

  it('shows reject form when Reject is clicked', async () => {
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(screen.getByPlaceholderText('Rejection reason (required)')).toBeInTheDocument()
  })

  it('disables Confirm Reject when reason is empty', async () => {
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(screen.getByRole('button', { name: 'Confirm Reject' })).toBeDisabled()
  })

  it('calls reject endpoint and shows toast on success', async () => {
    const removeOrder = vi.fn()
    vi.mocked(fetch).mockResolvedValue({ ok: true, status: 200 } as Response)
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    await userEvent.type(screen.getByPlaceholderText('Rejection reason (required)'), 'Out of budget')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))
    await waitFor(() => expect(removeOrder).toHaveBeenCalledWith('po-00000000-0001'))
  })

  it('shows error toast on reject failure', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 500 } as Response)
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    await userEvent.type(screen.getByPlaceholderText('Rejection reason (required)'), 'reason')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm Reject' }))
    await waitFor(() => expect(screen.getByText(/Reject failed: HTTP 500/)).toBeInTheDocument())
  })

  it('cancels reject form without submitting', async () => {
    mockedHook.mockReturnValue({ orders: [makePo()], loading: false, error: null, removeOrder: noop })
    render(<ApprovalWorkflowsTab />)
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByPlaceholderText('Rejection reason (required)')).not.toBeInTheDocument()
  })
})
