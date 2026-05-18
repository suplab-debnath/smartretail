import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import NarrativeHero from '../../components/NarrativeHero'
import type { FlowDef } from '../../types'

const flow = (overrides: Partial<FlowDef> = {}): FlowDef => ({
  id: 'flow1', chapterNumber: 1, title: 'POS Event Flow',
  subtitle: 'End-to-end POS ingestion', colorClass: 'from-blue-900 to-indigo-900',
  steps: [], ...overrides,
})

describe('NarrativeHero', () => {
  it('renders chapter number', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Step 1" stepNarrative="Narrative" />)
    expect(screen.getByText('Chapter 1')).toBeInTheDocument()
  })

  it('renders flow title', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Step 1" stepNarrative="Narrative" />)
    expect(screen.getByText('POS Event Flow')).toBeInTheDocument()
  })

  it('renders flow subtitle', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Step 1" stepNarrative="Narrative" />)
    expect(screen.getByText('End-to-end POS ingestion')).toBeInTheDocument()
  })

  it('renders step title', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Configure Kinesis" stepNarrative="Narrative" />)
    expect(screen.getByText('Configure Kinesis')).toBeInTheDocument()
  })

  it('renders step narrative', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Step 1" stepNarrative="Data flows from POS terminals" />)
    expect(screen.getByText('Data flows from POS terminals')).toBeInTheDocument()
  })

  it('renders SC_PLANNER persona badge', () => {
    render(<NarrativeHero flow={flow({ persona: 'SC_PLANNER' })} stepTitle="Step" stepNarrative="Narrative" />)
    expect(screen.getByText('SC_PLANNER')).toBeInTheDocument()
  })

  it('renders EXECUTIVE persona badge', () => {
    render(<NarrativeHero flow={flow({ persona: 'EXECUTIVE' })} stepTitle="Step" stepNarrative="Narrative" />)
    expect(screen.getByText('EXECUTIVE')).toBeInTheDocument()
  })

  it('renders STORE_MANAGER persona badge', () => {
    render(<NarrativeHero flow={flow({ persona: 'STORE_MANAGER' })} stepTitle="Step" stepNarrative="Narrative" />)
    expect(screen.getByText('STORE_MANAGER')).toBeInTheDocument()
  })

  it('renders unknown persona with fallback style', () => {
    render(<NarrativeHero flow={flow({ persona: 'UNKNOWN_ROLE' })} stepTitle="Step" stepNarrative="Narrative" />)
    expect(screen.getByText('UNKNOWN_ROLE')).toBeInTheDocument()
  })

  it('renders without persona badge when persona is absent', () => {
    render(<NarrativeHero flow={flow()} stepTitle="Step" stepNarrative="Narrative" />)
    expect(screen.queryByText('SC_PLANNER')).not.toBeInTheDocument()
    expect(screen.queryByText('EXECUTIVE')).not.toBeInTheDocument()
  })
})
