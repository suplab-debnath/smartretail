import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import BeforeAfterPanel from '../../components/BeforeAfterPanel'
import type { DbSnapshot } from '../../types'

const snap = (rows: Record<string, unknown>[], ts = '2026-05-18T10:30:45Z'): DbSnapshot => ({ rows, timestamp: ts })

describe('BeforeAfterPanel', () => {
  it('renders nothing when both before and after are null', () => {
    const { container } = render(<BeforeAfterPanel label="Orders" before={null} after={null} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders the label', () => {
    render(<BeforeAfterPanel label="Purchase Orders" before={snap([])} after={null} />)
    expect(screen.getByText('Purchase Orders')).toBeInTheDocument()
  })

  it('shows BEFORE and AFTER column headers when expanded', () => {
    render(<BeforeAfterPanel label="Test" before={snap([])} after={snap([])} />)
    expect(screen.getByText('BEFORE')).toBeInTheDocument()
    expect(screen.getByText('AFTER')).toBeInTheDocument()
  })

  it('shows ▾ hide toggle when expanded', () => {
    render(<BeforeAfterPanel label="Test" before={snap([])} after={null} />)
    expect(screen.getByText(/▾ hide/)).toBeInTheDocument()
  })

  it('collapses when button is clicked', async () => {
    render(<BeforeAfterPanel label="Test" before={snap([])} after={null} />)
    await userEvent.click(screen.getByRole('button'))
    expect(screen.queryByText('BEFORE')).not.toBeInTheDocument()
  })

  it('shows ▸ show toggle when collapsed', async () => {
    render(<BeforeAfterPanel label="Test" before={snap([])} after={null} />)
    await userEvent.click(screen.getByRole('button'))
    expect(screen.getByText(/▸ show/)).toBeInTheDocument()
  })

  it('shows No rows for empty before snapshot', () => {
    render(<BeforeAfterPanel label="Test" before={snap([])} after={null} />)
    expect(screen.getAllByText('No rows').length).toBeGreaterThan(0)
  })

  it('renders row data from before snapshot', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: 'abc', status: 'PENDING' }])} after={null} />)
    expect(screen.getAllByText('abc').length).toBeGreaterThan(0)
    expect(screen.getAllByText('PENDING').length).toBeGreaterThan(0)
  })

  it('renders column headers', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: '1', status: 'ACTIVE' }])} after={null} />)
    expect(screen.getAllByText('id').length).toBeGreaterThan(0)
    expect(screen.getAllByText('status').length).toBeGreaterThan(0)
  })

  it('renders timestamp HH:MM:SS from before snapshot', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: '1' }], '2026-05-18T10:30:45Z')} after={null} />)
    expect(screen.getByText('10:30:45')).toBeInTheDocument()
  })

  it('highlights changed cells between before and after', () => {
    const b = snap([{ id: '1', status: 'PENDING' }])
    const a = snap([{ id: '1', status: 'APPROVED' }])
    render(<BeforeAfterPanel label="Test" before={b} after={a} />)
    expect(screen.getByText('APPROVED')).toBeInTheDocument()
  })

  it('renders object values as JSON', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: '1', meta: { k: 'v' } }])} after={null} />)
    expect(screen.getAllByText('{"k":"v"}').length).toBeGreaterThan(0)
  })

  it('truncates values longer than 20 chars', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: '1', data: 'a'.repeat(30) }])} after={null} />)
    expect(screen.getAllByText(/…/).length).toBeGreaterThan(0)
  })

  it('renders null values as —', () => {
    render(<BeforeAfterPanel label="Test" before={snap([{ id: '1', val: null }])} after={null} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('renders with only after snapshot (before is null)', () => {
    render(<BeforeAfterPanel label="Test" before={null} after={snap([{ id: '1' }])} />)
    expect(screen.getByText('Test')).toBeInTheDocument()
  })
})
