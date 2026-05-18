import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ChapterView from '../../components/ChapterView'
import { useFlowRunner } from '../../hooks/useFlowRunner'
import { useDbSnapshot } from '../../hooks/useDbSnapshot'
import type { FlowDef } from '../../types'

vi.mock('../../hooks/useFlowRunner')
vi.mock('../../hooks/useDbSnapshot')

const mockedRunner = vi.mocked(useFlowRunner)
const mockedSnapshot = vi.mocked(useDbSnapshot)

const defaultRunner = { state: 'idle' as const, error: null, trigger: vi.fn(), reset: vi.fn() }
const defaultSnapshot = {
  before: null, after: null, polling: false,
  captureBefore: vi.fn(), startPolling: vi.fn(), reset: vi.fn(),
}

const makeFlow = (): FlowDef => ({
  id: 'flow1', chapterNumber: 1, title: 'POS Event Flow',
  subtitle: 'End-to-end POS ingestion', colorClass: 'from-blue-900 to-indigo-900',
  steps: [
    {
      id: 'step1',
      title: 'Send POS Event',
      narrative: 'A POS event is sent to Kinesis.',
      activeNodes: ['kinesis', 'sis'],
      flowEdges: [['kinesis', 'lambda']],
    },
    {
      id: 'step2',
      title: 'Verify Stock Alert',
      narrative: 'IMS raises an alert.',
      trigger: {
        label: 'Trigger POS Event',
        endpoint: '/api/trigger/flow1/pos-event',
        body: {},
        description: 'Sends event to Kinesis',
      },
      checklist: [
        { id: 'c1', text: 'Stock alert raised', matchPattern: 'STOCK_ALERT' },
      ],
      dbQueries: [
        { key: 'q1', label: 'Stock Alerts', endpoint: '/api/dbstate/alerts' },
      ],
    },
    {
      id: 'step3',
      title: 'View MFE',
      narrative: 'The store manager MFE is updated.',
      mfeReveal: {
        mfe: 'store-manager',
        localPort: 5173,
        path: '/dashboard',
        label: 'Store Manager Dashboard',
      },
    },
  ],
})

beforeEach(() => {
  vi.clearAllMocks()
  mockedRunner.mockReturnValue(defaultRunner)
  mockedSnapshot.mockReturnValue(defaultSnapshot)
})

describe('ChapterView', () => {
  it('renders the first step narrative', () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    expect(screen.getByText('A POS event is sent to Kinesis.')).toBeInTheDocument()
  })

  it('shows the prev step button disabled on the first step', () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    expect(screen.getByRole('button', { name: '← prev step' })).toBeDisabled()
  })

  it('shows next step button enabled on first step', () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    expect(screen.getByRole('button', { name: 'next step →' })).not.toBeDisabled()
  })

  it('navigates to next step when next is clicked', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(screen.getByText('IMS raises an alert.')).toBeInTheDocument()
  })

  it('navigates back to prev step', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    await userEvent.click(screen.getByRole('button', { name: '← prev step' }))
    expect(screen.getByText('A POS event is sent to Kinesis.')).toBeInTheDocument()
  })

  it('shows trigger button on step with trigger', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(screen.getByText('Trigger POS Event')).toBeInTheDocument()
  })

  it('shows checklist on step with checklist', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(screen.getByText('Stock alert raised')).toBeInTheDocument()
  })

  it('shows runner error message when present', () => {
    mockedRunner.mockReturnValue({ ...defaultRunner, state: 'failed', error: 'HTTP 500' })
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    expect(screen.getByText('HTTP 500')).toBeInTheDocument()
  })

  it('calls trigger when trigger button is clicked', async () => {
    const trigger = vi.fn()
    mockedRunner.mockReturnValue({ ...defaultRunner, trigger })
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    await userEvent.click(screen.getByRole('button', { name: /Trigger POS Event/ }))
    expect(trigger).toHaveBeenCalled()
  })

  it('navigates via step progress bar click', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByTitle('Verify Stock Alert'))
    expect(screen.getByText('IMS raises an alert.')).toBeInTheDocument()
  })

  it('shows MfeRevealPanel on step with mfeReveal', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(screen.getByText('Store Manager Dashboard')).toBeInTheDocument()
  })

  it('next step disabled on last step', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(screen.getByRole('button', { name: 'next step →' })).toBeDisabled()
  })

  it('returns null when flow has no steps', () => {
    const { container } = render(<ChapterView flow={{ ...makeFlow(), steps: [] }} hasMatch={() => false} />)
    expect(container.firstChild).toBeNull()
  })

  it('mounts DbQueryPanel on step with dbQueries', async () => {
    render(<ChapterView flow={makeFlow()} hasMatch={() => false} />)
    await userEvent.click(screen.getByRole('button', { name: 'next step →' }))
    expect(mockedSnapshot).toHaveBeenCalledWith('/api/dbstate/alerts', undefined)
  })
})
