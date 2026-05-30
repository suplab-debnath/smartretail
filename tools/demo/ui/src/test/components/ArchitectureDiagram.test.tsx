import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import ArchitectureDiagram from '../../components/ArchitectureDiagram'

describe('ArchitectureDiagram', () => {
  it('renders SVG with aria-label', () => {
    render(<ArchitectureDiagram nodeStates={{}} />)
    expect(screen.getByLabelText('SmartRetail service architecture')).toBeInTheDocument()
  })

  it('renders all service node labels', () => {
    render(<ArchitectureDiagram nodeStates={{}} />)
    for (const label of ['Firehose', 'SIS', 'IMS', 'RE', 'ARS', 'DFS', 'SUP', 'RDS']) {
      expect(screen.getAllByText(label).length).toBeGreaterThanOrEqual(1)
    }
  })

  it('renders SQS and EventBridge nodes', () => {
    render(<ArchitectureDiagram nodeStates={{}} />)
    expect(screen.getByText('EventBridge')).toBeInTheDocument()
    expect(screen.getByText('SQS (RE)')).toBeInTheDocument()
  })

  it('renders three MFE box labels', () => {
    render(<ArchitectureDiagram nodeStates={{}} />)
    expect(screen.getAllByText(/Store Manager/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/SC Planner/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/Executive/).length).toBeGreaterThanOrEqual(1)
  })

  it('renders with active node states', () => {
    render(<ArchitectureDiagram nodeStates={{ sis: 'active', ims: 'success', re: 'error', rds: 'idle' }} />)
    expect(screen.getByLabelText('SmartRetail service architecture')).toBeInTheDocument()
  })

  it('renders with active edges', () => {
    render(<ArchitectureDiagram nodeStates={{ sis: 'active' }} activeEdges={[['sis', 'eventbridge'], ['firehose', 'sis']]} />)
    expect(screen.getByLabelText('SmartRetail service architecture')).toBeInTheDocument()
  })

  it('renders without activeEdges prop', () => {
    render(<ArchitectureDiagram nodeStates={{}} />)
    expect(screen.getByLabelText('SmartRetail service architecture')).toBeInTheDocument()
  })

  it('renders with unknown node id in activeEdges gracefully', () => {
    render(<ArchitectureDiagram nodeStates={{}} activeEdges={[['sis', 'unknown' as never]]} />)
    expect(screen.getByLabelText('SmartRetail service architecture')).toBeInTheDocument()
  })
})
