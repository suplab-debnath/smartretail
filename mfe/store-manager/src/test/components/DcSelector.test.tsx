import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { DcSelector } from '../../components/DcSelector'

describe('DcSelector', () => {
  it('renders the select with all DC options', () => {
    render(<DcSelector value="DC-LONDON" onChange={vi.fn()} />)
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByText('DC-LONDON')).toBeInTheDocument()
    expect(screen.getByText('DC-MANCHESTER')).toBeInTheDocument()
    expect(screen.getByText('DC-BIRMINGHAM')).toBeInTheDocument()
  })

  it('shows the currently selected value', () => {
    render(<DcSelector value="DC-MANCHESTER" onChange={vi.fn()} />)
    const select = screen.getByRole('combobox') as HTMLSelectElement
    expect(select.value).toBe('DC-MANCHESTER')
  })

  it('calls onChange with selected DC when changed', async () => {
    const onChange = vi.fn()
    render(<DcSelector value="DC-LONDON" onChange={onChange} />)
    const select = screen.getByRole('combobox')
    await userEvent.selectOptions(select, 'DC-BIRMINGHAM')
    expect(onChange).toHaveBeenCalledWith('DC-BIRMINGHAM')
  })

  it('renders a label for the select', () => {
    render(<DcSelector value="DC-LONDON" onChange={vi.fn()} />)
    expect(screen.getByText('Distribution Centre')).toBeInTheDocument()
  })
})
