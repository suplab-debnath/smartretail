import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import FlowRail from '../../components/FlowRail'
import type { FlowDef, FlowStatus } from '../../types'

const makeFlow = (id: string, num: number, title: string): FlowDef => ({
  id, chapterNumber: num, title, subtitle: `Subtitle for ${title}`,
  colorClass: 'from-slate-800 to-slate-900', steps: [],
})

const flows = [
  makeFlow('flow1', 1, 'POS Ingest'),
  makeFlow('flow2', 2, 'Alert Processing'),
  makeFlow('flow3', 3, 'SC Planner'),
]

const statuses: Record<string, FlowStatus> = {
  flow1: 'complete',
  flow2: 'in_progress',
  flow3: 'not_started',
}

describe('FlowRail', () => {
  it('renders all flow titles', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={vi.fn()} />)
    expect(screen.getByText('POS Ingest')).toBeInTheDocument()
    expect(screen.getByText('Alert Processing')).toBeInTheDocument()
    expect(screen.getByText('SC Planner')).toBeInTheDocument()
  })

  it('renders chapter numbers padded to 2 digits', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={vi.fn()} />)
    expect(screen.getByText('01')).toBeInTheDocument()
    expect(screen.getByText('02')).toBeInTheDocument()
    expect(screen.getByText('03')).toBeInTheDocument()
  })

  it('calls onSelect with flow id when a flow is clicked', async () => {
    const onSelect = vi.fn()
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={onSelect} />)
    await userEvent.click(screen.getByRole('button', { name: /Alert Processing/ }))
    expect(onSelect).toHaveBeenCalledWith('flow2')
  })

  it('renders header text', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={vi.fn()} />)
    expect(screen.getByText('SmartRetail')).toBeInTheDocument()
    expect(screen.getByText('Demo Control Center')).toBeInTheDocument()
  })

  it('renders status legend entries', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={vi.fn()} />)
    expect(screen.getByText('not started')).toBeInTheDocument()
    expect(screen.getByText('in progress')).toBeInTheDocument()
    expect(screen.getByText('complete')).toBeInTheDocument()
    expect(screen.getByText('failed')).toBeInTheDocument()
  })

  it('renders flow subtitles', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={statuses} onSelect={vi.fn()} />)
    expect(screen.getByText('Subtitle for POS Ingest')).toBeInTheDocument()
  })

  it('uses not_started as default when flow not in statuses', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={{}} onSelect={vi.fn()} />)
    expect(screen.getByText('POS Ingest')).toBeInTheDocument()
  })

  it('renders failed status flow', () => {
    render(<FlowRail flows={flows} activeId="flow1" statuses={{ flow1: 'failed' }} onSelect={vi.fn()} />)
    expect(screen.getByText('POS Ingest')).toBeInTheDocument()
  })
})
