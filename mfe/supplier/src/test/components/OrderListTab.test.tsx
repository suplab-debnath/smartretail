import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { OrderListTab } from '../../components/OrderListTab'
import type { SupplierOrder } from '../../hooks/useSupplierOrders'

const makeOrder = (overrides: Partial<SupplierOrder> = {}): SupplierOrder => ({
  supplierPoId: `spo-${Math.random()}`,
  poId: 'c1b2c3d4-0000-0000-0000-000000000001',
  supplierId: 'sup-1',
  supplierName: 'Acme Beverages Ltd',
  skuId: 'SKU-BEV-001',
  dcId: 'DC-LONDON',
  quantity: 500,
  shipmentStatus: 'DISPATCHED',
  confirmedAt: null,
  dispatchedAt: '2026-05-17T00:00:00Z',
  eta: '2026-05-20',
  lastUpdateAt: '2026-05-17T08:00:00Z',
  ...overrides,
})

const makePage = (count: number): SupplierOrder[] =>
  Array.from({ length: count }, (_, i) =>
    makeOrder({ supplierPoId: `spo-${i}`, skuId: `SKU-${i.toString().padStart(3, '0')}` })
  )

describe('OrderListTab', () => {
  it('shows empty state when orders is empty', () => {
    render(<OrderListTab orders={[]} />)
    expect(screen.getByText('No orders found')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('renders a row for each order', () => {
    const orders = [makeOrder({ skuId: 'SKU-AAA' }), makeOrder({ skuId: 'SKU-BBB' })]
    render(<OrderListTab orders={orders} />)
    expect(screen.getByText('SKU-AAA')).toBeInTheDocument()
    expect(screen.getByText('SKU-BBB')).toBeInTheDocument()
  })

  it('renders truncated PO reference (first 8 chars + ellipsis)', () => {
    render(<OrderListTab orders={[makeOrder()]} />)
    // poId is 'c1b2c3d4-0000-0000-0000-000000000001', first 8 = 'c1b2c3d4'
    expect(screen.getByText('c1b2c3d4…')).toBeInTheDocument()
  })

  it('renders quantity with toLocaleString formatting', () => {
    render(<OrderListTab orders={[makeOrder({ quantity: 1500 })]} />)
    expect(screen.getByText('1,500')).toBeInTheDocument()
  })

  it('renders — for null eta', () => {
    render(<OrderListTab orders={[makeOrder({ eta: null })]} />)
    const etaCells = screen.getAllByText('—')
    expect(etaCells.length).toBeGreaterThan(0)
  })

  it('renders — for null lastUpdateAt', () => {
    render(<OrderListTab orders={[makeOrder({ lastUpdateAt: null })]} />)
    const dashCells = screen.getAllByText('—')
    expect(dashCells.length).toBeGreaterThan(0)
  })

  it('renders shipment status badge', () => {
    render(<OrderListTab orders={[makeOrder({ shipmentStatus: 'EXCEPTION' })]} />)
    expect(screen.getByText('EXCEPTION')).toBeInTheDocument()
  })

  describe('sorting', () => {
    it('sorts by SKU ascending when SKU header is clicked', async () => {
      const orders = [
        makeOrder({ skuId: 'SKU-ZZZ', eta: '2026-05-25' }),
        makeOrder({ skuId: 'SKU-AAA', eta: '2026-05-22' }),
      ]
      render(<OrderListTab orders={orders} />)
      // Target the column header specifically, not cell values
      await userEvent.click(screen.getByRole('columnheader', { name: /^sku/i }))
      const rows = screen.getAllByRole('row').slice(1) // skip header row
      expect(within(rows[0]).getByText('SKU-AAA')).toBeInTheDocument()
      expect(within(rows[1]).getByText('SKU-ZZZ')).toBeInTheDocument()
    })

    it('toggles to descending on second click of same header', async () => {
      const orders = [
        makeOrder({ skuId: 'SKU-AAA' }),
        makeOrder({ skuId: 'SKU-ZZZ' }),
      ]
      render(<OrderListTab orders={orders} />)
      const skuHeader = screen.getByRole('columnheader', { name: /^sku/i })
      await userEvent.click(skuHeader) // asc → SKU ↑
      await userEvent.click(screen.getByRole('columnheader', { name: /sku.*↑/i })) // desc
      const rows = screen.getAllByRole('row').slice(1)
      expect(within(rows[0]).getByText('SKU-ZZZ')).toBeInTheDocument()
      expect(within(rows[1]).getByText('SKU-AAA')).toBeInTheDocument()
    })

    it('shows sort arrow indicator on active column', async () => {
      render(<OrderListTab orders={[makeOrder()]} />)
      await userEvent.click(screen.getByRole('columnheader', { name: /^dc/i }))
      expect(screen.getByRole('columnheader', { name: /dc.*↑/i })).toBeInTheDocument()
    })
  })

  describe('pagination', () => {
    it('does not show pagination controls when 10 or fewer orders', () => {
      render(<OrderListTab orders={makePage(10)} />)
      expect(screen.queryByRole('button', { name: /previous/i })).not.toBeInTheDocument()
    })

    it('shows pagination controls when more than 10 orders', () => {
      render(<OrderListTab orders={makePage(11)} />)
      expect(screen.getByRole('button', { name: /previous/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /next/i })).toBeInTheDocument()
    })

    it('Previous button is disabled on first page', () => {
      render(<OrderListTab orders={makePage(11)} />)
      expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()
    })

    it('navigates to next page and back', async () => {
      render(<OrderListTab orders={makePage(11)} />)
      await userEvent.click(screen.getByRole('button', { name: /next/i }))
      expect(screen.getByRole('button', { name: /previous/i })).not.toBeDisabled()
      expect(screen.getByText(/Showing 11–11 of 11 orders/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /previous/i }))
      expect(screen.getByText(/Showing 1–10 of 11 orders/)).toBeInTheDocument()
    })

    it('Next button is disabled on last page', async () => {
      render(<OrderListTab orders={makePage(11)} />)
      await userEvent.click(screen.getByRole('button', { name: /next/i }))
      expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
    })
  })
})
