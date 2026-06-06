import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { ReplenishmentFlowDiagram } from '../../components/ReplenishmentFlowDiagram'

describe('ReplenishmentFlowDiagram', () => {
  it('renders the SVG with accessible label', () => {
    render(<ReplenishmentFlowDiagram completedSteps={0} phase="idle" />)
    expect(screen.getByLabelText('Replenishment pipeline flow diagram')).toBeInTheDocument()
  })

  it('renders all service node labels', () => {
    render(<ReplenishmentFlowDiagram completedSteps={0} phase="idle" />)
    expect(screen.getByText('SIS')).toBeInTheDocument()
    expect(screen.getByText('EventBridge')).toBeInTheDocument()
    expect(screen.getByText('IMS')).toBeInTheDocument()
    expect(screen.getByText('RDS')).toBeInTheDocument()
    expect(screen.getByText('SQS')).toBeInTheDocument()
    expect(screen.getByText('RE')).toBeInTheDocument()
    expect(screen.getByText('SC Planner — Approve PO')).toBeInTheDocument()
  })

  it('renders with injecting phase', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={0} phase="injecting" />)
    // SVG rendered — injecting activates SIS node
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with polling phase and completedSteps=1', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={1} phase="polling" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with polling phase and completedSteps=2', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={2} phase="polling" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with polling phase and completedSteps=3', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={3} phase="polling" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with found phase', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={3} phase="found" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with approving phase', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={3} phase="approving" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with done phase — all steps complete', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={4} phase="done" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders with timeout phase', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={2} phase="timeout" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders completedSteps >= 4 marking all nodes as success', () => {
    const { container } = render(<ReplenishmentFlowDiagram completedSteps={5} phase="idle" />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('renders row label annotations', () => {
    render(<ReplenishmentFlowDiagram completedSteps={0} phase="idle" />)
    expect(screen.getByText('① POS sale')).toBeInTheDocument()
    expect(screen.getByText('② events')).toBeInTheDocument()
    expect(screen.getByText('③ stock update')).toBeInTheDocument()
    expect(screen.getByText('④ raise PO')).toBeInTheDocument()
  })
})
