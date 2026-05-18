import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import StepProgressBar from '../../components/StepProgressBar'
import type { StepDef } from '../../types'

const makeStep = (id: string, title: string): StepDef => ({ id, title, narrative: `Narrative for ${title}` })

const steps = [
  makeStep('step1', 'Configure Kinesis'),
  makeStep('step2', 'Send POS Event'),
  makeStep('step3', 'Verify Stock Alert'),
]

describe('StepProgressBar', () => {
  it('renders a button for each step', () => {
    render(<StepProgressBar steps={steps} activeStepId="step1" onSelect={vi.fn()} />)
    expect(screen.getAllByRole('button')).toHaveLength(3)
  })

  it('shows checkmark for steps before the active step', () => {
    render(<StepProgressBar steps={steps} activeStepId="step3" onSelect={vi.fn()} />)
    expect(screen.getAllByText('✓')).toHaveLength(2)
  })

  it('shows numbers for pending steps', () => {
    render(<StepProgressBar steps={steps} activeStepId="step1" onSelect={vi.fn()} />)
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('shows active step title in label', () => {
    render(<StepProgressBar steps={steps} activeStepId="step2" onSelect={vi.fn()} />)
    expect(screen.getByText('Send POS Event')).toBeInTheDocument()
  })

  it('calls onSelect with step id when a step button is clicked', async () => {
    const onSelect = vi.fn()
    render(<StepProgressBar steps={steps} activeStepId="step2" onSelect={onSelect} />)
    await userEvent.click(screen.getAllByRole('button')[0])
    expect(onSelect).toHaveBeenCalledWith('step1')
  })

  it('shows step 1 as first in sequence', () => {
    render(<StepProgressBar steps={steps} activeStepId="step1" onSelect={vi.fn()} />)
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('renders with a single step', () => {
    render(<StepProgressBar steps={[makeStep('s1', 'Only Step')]} activeStepId="s1" onSelect={vi.fn()} />)
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.queryByText('2')).not.toBeInTheDocument()
  })

  it('has title attribute on each step button', () => {
    render(<StepProgressBar steps={steps} activeStepId="step1" onSelect={vi.fn()} />)
    expect(screen.getByTitle('Send POS Event')).toBeInTheDocument()
    expect(screen.getByTitle('Verify Stock Alert')).toBeInTheDocument()
  })
})
