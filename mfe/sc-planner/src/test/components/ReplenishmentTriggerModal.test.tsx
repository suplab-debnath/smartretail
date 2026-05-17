import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ReplenishmentTriggerModal } from '../../components/ReplenishmentTriggerModal'

describe('ReplenishmentTriggerModal', () => {
  const defaultProps = {
    skuId: 'SKU-BEV-001',
    dcId: 'DC-LONDON',
    onClose: vi.fn(),
    onSuccess: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('fetch', vi.fn())
  })

  it('renders SKU and DC as read-only fields', () => {
    render(<ReplenishmentTriggerModal {...defaultProps} />)
    const skuInput = screen.getByDisplayValue('SKU-BEV-001') as HTMLInputElement
    const dcInput  = screen.getByDisplayValue('DC-LONDON') as HTMLInputElement
    expect(skuInput.readOnly).toBe(true)
    expect(dcInput.readOnly).toBe(true)
  })

  it('renders quantity field with default value 1', () => {
    render(<ReplenishmentTriggerModal {...defaultProps} />)
    const qty = screen.getByRole('spinbutton') as HTMLInputElement
    expect(qty.value).toBe('1')
  })

  it('calls onClose when Cancel is clicked', async () => {
    render(<ReplenishmentTriggerModal {...defaultProps} />)
    await userEvent.click(screen.getByText('Cancel'))
    expect(defaultProps.onClose).toHaveBeenCalledOnce()
  })

  it('submits form and calls onSuccess on 201 response', async () => {
    vi.mocked(fetch).mockResolvedValue({
      status: 201,
      json: async () => ({ poId: 'po-uuid-123' }),
    } as Response)

    render(<ReplenishmentTriggerModal {...defaultProps} />)
    await userEvent.click(screen.getByRole('button', { name: /submit order/i }))

    await waitFor(() => {
      expect(defaultProps.onSuccess).toHaveBeenCalledWith('po-uuid-123')
      expect(defaultProps.onClose).toHaveBeenCalled()
    })
  })

  it('shows conflict error on 409 response', async () => {
    vi.mocked(fetch).mockResolvedValue({
      status: 409,
      json: async () => ({}),
    } as Response)

    render(<ReplenishmentTriggerModal {...defaultProps} />)
    await userEvent.click(screen.getByRole('button', { name: /submit order/i }))

    await waitFor(() => {
      expect(screen.getByText(/PENDING order already exists/)).toBeInTheDocument()
    })
  })

  it('shows HTTP error for unexpected status', async () => {
    vi.mocked(fetch).mockResolvedValue({
      status: 500,
      json: async () => ({}),
    } as Response)

    render(<ReplenishmentTriggerModal {...defaultProps} />)
    await userEvent.click(screen.getByRole('button', { name: /submit order/i }))

    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
    })
  })

  it('shows error when fetch throws', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('Network failure'))

    render(<ReplenishmentTriggerModal {...defaultProps} />)
    await userEvent.click(screen.getByRole('button', { name: /submit order/i }))

    await waitFor(() => {
      expect(screen.getByText('Network failure')).toBeInTheDocument()
    })
  })

  it('renders title', () => {
    render(<ReplenishmentTriggerModal {...defaultProps} />)
    expect(screen.getByText('Trigger Replenishment')).toBeInTheDocument()
  })
})
