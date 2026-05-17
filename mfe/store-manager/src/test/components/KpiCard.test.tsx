import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { KpiCard } from '../../components/KpiCard'

describe('KpiCard', () => {
  it('renders label and value', () => {
    render(<KpiCard label="Active Alerts" value={42} />)
    expect(screen.getByText('Active Alerts')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('renders string value', () => {
    render(<KpiCard label="Coverage" value="87.5%" />)
    expect(screen.getByText('87.5%')).toBeInTheDocument()
  })

  it('renders sub-items when provided', () => {
    const subItems = [
      { label: 'Critical', value: 3, color: 'text-red-500' },
      { label: 'High', value: 5, color: 'text-orange-500' },
    ]
    render(<KpiCard label="KPI" value={8} subItems={subItems} />)
    expect(screen.getByText('Critical: 3')).toBeInTheDocument()
    expect(screen.getByText('High: 5')).toBeInTheDocument()
  })

  it('renders without sub-items when not provided', () => {
    const { container } = render(<KpiCard label="KPI" value={0} />)
    const spans = container.querySelectorAll('.text-xs')
    expect(spans).toHaveLength(0)
  })

  it('renders empty subItems without sub-item spans', () => {
    const { container } = render(<KpiCard label="KPI" value={0} subItems={[]} />)
    const subItemContainer = container.querySelector('.flex.gap-3')
    expect(subItemContainer).not.toBeInTheDocument()
  })
})
