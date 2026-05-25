import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import EventLogEntryRow from '../../components/EventLogEntry'
import type { EventLogEntry } from '../../types'

const baseEntry: EventLogEntry = {
  id: 'e1',
  ts: '2024-05-17T10:23:45.000Z',
  flowId: 'flow1',
  stepId: 'step1',
  service: 'SIS',
  level: 'pass',
  message: 'Sales event ingested',
  raw: '{}',
}

describe('EventLogEntryRow', () => {
  it('renders the message', () => {
    render(<EventLogEntryRow entry={baseEntry} />)
    expect(screen.getByText('Sales event ingested')).toBeInTheDocument()
  })

  it('renders time in HH:MM:SS format', () => {
    render(<EventLogEntryRow entry={baseEntry} />)
    expect(screen.getByText('10:23:45')).toBeInTheDocument()
  })

  it('renders service label truncated to 6 chars', () => {
    render(<EventLogEntryRow entry={{ ...baseEntry, service: 'eventbridge' }} />)
    expect(screen.getByText('eventb')).toBeInTheDocument()
  })

  it('shows pass prefix ✓ for pass level', () => {
    render(<EventLogEntryRow entry={baseEntry} />)
    expect(screen.getByText('✓')).toBeInTheDocument()
  })

  it('shows fail prefix ✗ for fail level', () => {
    render(<EventLogEntryRow entry={{ ...baseEntry, level: 'fail' }} />)
    expect(screen.getByText('✗')).toBeInTheDocument()
  })

  it('applies emerald color class for pass level', () => {
    render(<EventLogEntryRow entry={baseEntry} />)
    expect(screen.getByText('Sales event ingested').className).toContain('text-emerald-400')
  })

  it('applies red color class for fail level', () => {
    render(<EventLogEntryRow entry={{ ...baseEntry, level: 'fail', message: 'Ingestion failed' }} />)
    expect(screen.getByText('Ingestion failed').className).toContain('text-red-400')
  })
})
