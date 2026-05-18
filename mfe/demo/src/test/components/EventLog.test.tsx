import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import EventLog from '../../components/EventLog'
import type { EventLogEntry } from '../../types'

beforeEach(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn()
})

const makeEntry = (id: string): EventLogEntry => ({
  id, ts: '2026-05-18T10:00:00Z',
  flowId: 'flow1', stepId: 'step1',
  service: 'SIS', level: 'pass',
  message: `Event ${id}`, raw: `raw ${id}`,
})

describe('EventLog', () => {
  it('renders the Event Log heading', () => {
    render(<EventLog entries={[]} onClear={vi.fn()} />)
    expect(screen.getByText('Event Log')).toBeInTheDocument()
  })

  it('shows waiting message when no entries', () => {
    render(<EventLog entries={[]} onClear={vi.fn()} />)
    expect(screen.getByText('Waiting for events…')).toBeInTheDocument()
  })

  it('shows entry count', () => {
    render(<EventLog entries={[makeEntry('e1'), makeEntry('e2')]} onClear={vi.fn()} />)
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('shows 0 when no entries', () => {
    render(<EventLog entries={[]} onClear={vi.fn()} />)
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('renders entry messages', () => {
    render(<EventLog entries={[makeEntry('e1'), makeEntry('e2')]} onClear={vi.fn()} />)
    expect(screen.getByText('Event e1')).toBeInTheDocument()
    expect(screen.getByText('Event e2')).toBeInTheDocument()
  })

  it('calls onClear when clear button is clicked', async () => {
    const onClear = vi.fn()
    render(<EventLog entries={[]} onClear={onClear} />)
    await userEvent.click(screen.getByRole('button', { name: 'clear' }))
    expect(onClear).toHaveBeenCalled()
  })

  it('renders clear button', () => {
    render(<EventLog entries={[]} onClear={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'clear' })).toBeInTheDocument()
  })
})
