import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SupplierRankingTable } from '../../components/SupplierRankingTable'
import type { SupplierPerformanceEntry } from '../../types'

const makeSupplier = (overrides: Partial<SupplierPerformanceEntry> = {}): SupplierPerformanceEntry => ({
  supplierId: 'supplier-001',
  supplierName: 'Alpha Goods',
  otdRate: 0.92,
  fillRate: 0.95,
  earlyCount: 5,
  onTimeCount: 15,
  lateCount: 2,
  openExceptions: 0,
  ...overrides,
})

describe('SupplierRankingTable', () => {
  it('renders table headers', () => {
    render(<SupplierRankingTable suppliers={[]} />)
    expect(screen.getByText('Supplier')).toBeInTheDocument()
    expect(screen.getByText('OTD %')).toBeInTheDocument()
    expect(screen.getByText('Fill Rate')).toBeInTheDocument()
  })

  it('renders a supplier row with correct data', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier()]} />)
    expect(screen.getByText('Alpha Goods')).toBeInTheDocument()
    expect(screen.getByText('92%')).toBeInTheDocument()
    expect(screen.getByText('95%')).toBeInTheDocument()
  })

  it('ranks suppliers starting from 1', () => {
    const suppliers = [
      makeSupplier({ supplierId: 'a', supplierName: 'First' }),
      makeSupplier({ supplierId: 'b', supplierName: 'Second' }),
    ]
    render(<SupplierRankingTable suppliers={suppliers} />)
    const rankCells = screen.getAllByText(/^[12]$/)
    expect(rankCells[0].textContent).toBe('1')
    expect(rankCells[1].textContent).toBe('2')
  })

  it('shows exception badge when openExceptions > 0', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier({ openExceptions: 3 })]} />)
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('shows dash when openExceptions is 0', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier({ openExceptions: 0 })]} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('uses green color for OTD >= 90%', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier({ otdRate: 0.95, fillRate: 0.88 })]} />)
    const otdBadge = screen.getByText('95%')
    expect(otdBadge.className).toContain('text-green-700')
  })

  it('uses amber color for OTD between 75% and 90%', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier({ otdRate: 0.80, fillRate: 0.70 })]} />)
    const otdBadge = screen.getByText('80%')
    expect(otdBadge.className).toContain('text-amber-700')
  })

  it('uses red color for OTD < 75%', () => {
    render(<SupplierRankingTable suppliers={[makeSupplier({ otdRate: 0.60, fillRate: 0.55 })]} />)
    const otdBadge = screen.getByText('60%')
    expect(otdBadge.className).toContain('text-red-700')
  })

  it('renders empty state with no rows', () => {
    render(<SupplierRankingTable suppliers={[]} />)
    expect(screen.queryByRole('cell')).not.toBeInTheDocument()
  })
})
