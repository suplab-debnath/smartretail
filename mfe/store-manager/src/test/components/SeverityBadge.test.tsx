import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SeverityBadge } from '../../components/SeverityBadge'

describe('SeverityBadge', () => {
  it('renders CRITICAL with red styling', () => {
    render(<SeverityBadge severity="CRITICAL" />)
    const badge = screen.getByText('CRITICAL')
    expect(badge).toBeInTheDocument()
    expect(badge.className).toContain('text-red-800')
  })

  it('renders HIGH with orange styling', () => {
    render(<SeverityBadge severity="HIGH" />)
    const badge = screen.getByText('HIGH')
    expect(badge).toBeInTheDocument()
    expect(badge.className).toContain('text-orange-800')
  })

  it('renders MEDIUM with yellow styling', () => {
    render(<SeverityBadge severity="MEDIUM" />)
    const badge = screen.getByText('MEDIUM')
    expect(badge.className).toContain('text-yellow-800')
  })

  it('renders as a span element', () => {
    const { container } = render(<SeverityBadge severity="HIGH" />)
    expect(container.querySelector('span')).toBeInTheDocument()
  })
})
