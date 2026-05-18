import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import MissionControlLayout from '../../components/MissionControlLayout'

describe('MissionControlLayout', () => {
  it('renders rail content', () => {
    render(<MissionControlLayout rail={<div>Rail</div>} center={<div>Center</div>} logPane={<div>Log</div>} />)
    expect(screen.getByText('Rail')).toBeInTheDocument()
  })

  it('renders center content', () => {
    render(<MissionControlLayout rail={<div>Rail</div>} center={<div>Center</div>} logPane={<div>Log</div>} />)
    expect(screen.getByText('Center')).toBeInTheDocument()
  })

  it('renders logPane content', () => {
    render(<MissionControlLayout rail={<div>Rail</div>} center={<div>Center</div>} logPane={<div>Log</div>} />)
    expect(screen.getByText('Log')).toBeInTheDocument()
  })

  it('renders topBar when provided', () => {
    render(<MissionControlLayout rail={<div>Rail</div>} center={<div>Center</div>} logPane={<div>Log</div>} topBar={<div>Top Bar</div>} />)
    expect(screen.getByText('Top Bar')).toBeInTheDocument()
  })

  it('does not render topBar container when omitted', () => {
    render(<MissionControlLayout rail={<div>Rail</div>} center={<div>Center</div>} logPane={<div>Log</div>} />)
    expect(screen.queryByText('Top Bar')).not.toBeInTheDocument()
  })
})
