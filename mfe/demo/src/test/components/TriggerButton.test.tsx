import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import TriggerButton from '../../components/TriggerButton'

const baseProps = {
  label: 'Trigger Flow 1',
  description: 'Publishes a POS event',
  onTrigger: vi.fn(),
}

describe('TriggerButton', () => {
  it('shows label in idle state', () => {
    render(<TriggerButton {...baseProps} state="idle" />)
    expect(screen.getByText('Trigger Flow 1')).toBeInTheDocument()
  })

  it('shows Running text when state is running', () => {
    render(<TriggerButton {...baseProps} state="running" />)
    expect(screen.getByText('Running…')).toBeInTheDocument()
  })

  it('is disabled when state is running', () => {
    render(<TriggerButton {...baseProps} state="running" />)
    expect(screen.getByRole('button')).toBeDisabled()
  })

  it('shows done message when state is done', () => {
    render(<TriggerButton {...baseProps} state="done" />)
    expect(screen.getByText('Done — check the log')).toBeInTheDocument()
  })

  it('shows failed message when state is failed', () => {
    render(<TriggerButton {...baseProps} state="failed" />)
    expect(screen.getByText('Failed — retry?')).toBeInTheDocument()
  })

  it('calls onTrigger when clicked in idle state', async () => {
    const onTrigger = vi.fn()
    render(<TriggerButton {...baseProps} state="idle" onTrigger={onTrigger} />)
    await userEvent.click(screen.getByRole('button'))
    expect(onTrigger).toHaveBeenCalledOnce()
  })

  it('renders description text', () => {
    render(<TriggerButton {...baseProps} state="idle" />)
    expect(screen.getByText('Publishes a POS event')).toBeInTheDocument()
  })
})
