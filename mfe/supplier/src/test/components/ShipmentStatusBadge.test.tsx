import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { ShipmentStatusBadge } from '../../components/ShipmentStatusBadge'

describe('ShipmentStatusBadge', () => {
  const allStatuses = [
    'PENDING',
    'CONFIRMED',
    'DISPATCHED',
    'DELIVERED',
    'COMPLETED',
    'EXCEPTION',
  ] as const

  it.each(allStatuses)('renders the %s status text', (status) => {
    render(<ShipmentStatusBadge status={status} />)
    expect(screen.getByText(status)).toBeInTheDocument()
  })

  it('applies red styling for EXCEPTION status', () => {
    render(<ShipmentStatusBadge status="EXCEPTION" />)
    const badge = screen.getByText('EXCEPTION')
    expect(badge.className).toMatch(/red/)
  })

  it('applies green styling for DELIVERED status', () => {
    render(<ShipmentStatusBadge status="DELIVERED" />)
    const badge = screen.getByText('DELIVERED')
    expect(badge.className).toMatch(/green/)
  })

  it('applies amber styling for DISPATCHED status', () => {
    render(<ShipmentStatusBadge status="DISPATCHED" />)
    const badge = screen.getByText('DISPATCHED')
    expect(badge.className).toMatch(/amber/)
  })

  it('applies blue styling for CONFIRMED status', () => {
    render(<ShipmentStatusBadge status="CONFIRMED" />)
    const badge = screen.getByText('CONFIRMED')
    expect(badge.className).toMatch(/blue/)
  })

  it('applies gray styling for PENDING status', () => {
    render(<ShipmentStatusBadge status="PENDING" />)
    const badge = screen.getByText('PENDING')
    expect(badge.className).toMatch(/gray/)
  })

  it('renders as an inline span element', () => {
    render(<ShipmentStatusBadge status="COMPLETED" />)
    const badge = screen.getByText('COMPLETED')
    expect(badge.tagName.toLowerCase()).toBe('span')
  })
})
