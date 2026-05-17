import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SeverityBadge } from '../../components/SeverityBadge'

describe('SeverityBadge', () => {
  it('renders CRITICAL severity', () => {
    render(<SeverityBadge severity="CRITICAL" />)
    expect(screen.getByText('CRITICAL')).toBeInTheDocument()
  })

  it('renders HIGH severity', () => {
    render(<SeverityBadge severity="HIGH" />)
    expect(screen.getByText('HIGH')).toBeInTheDocument()
  })

  it('renders MEDIUM severity', () => {
    render(<SeverityBadge severity="MEDIUM" />)
    expect(screen.getByText('MEDIUM')).toBeInTheDocument()
  })

  it('applies red styling for CRITICAL', () => {
    render(<SeverityBadge severity="CRITICAL" />)
    const badge = screen.getByText('CRITICAL')
    expect(badge.className).toContain('text-red-700')
  })

  it('applies amber styling for HIGH', () => {
    render(<SeverityBadge severity="HIGH" />)
    const badge = screen.getByText('HIGH')
    expect(badge.className).toContain('text-amber-700')
  })

  it('applies yellow styling for MEDIUM', () => {
    render(<SeverityBadge severity="MEDIUM" />)
    const badge = screen.getByText('MEDIUM')
    expect(badge.className).toContain('text-yellow-700')
  })
})
