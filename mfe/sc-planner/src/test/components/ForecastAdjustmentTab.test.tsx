import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { ForecastAdjustmentTab } from '../../components/ForecastAdjustmentTab'

describe('ForecastAdjustmentTab', () => {
  it('renders the heading', () => {
    render(<ForecastAdjustmentTab upliftPercent={0} setUpliftPercent={vi.fn()} />)
    expect(screen.getByText('Forecast Adjustment Controls')).toBeInTheDocument()
  })

  it('renders uplift input with current value', () => {
    render(<ForecastAdjustmentTab upliftPercent={15} setUpliftPercent={vi.fn()} />)
    const input = screen.getByRole('spinbutton') as HTMLInputElement
    expect(input.value).toBe('15')
  })

  it('shows promo badge when uplift > 0', () => {
    render(<ForecastAdjustmentTab upliftPercent={20} setUpliftPercent={vi.fn()} />)
    expect(screen.getByText('Promo uplift: +20%')).toBeInTheDocument()
  })

  it('hides promo badge when uplift is 0', () => {
    render(<ForecastAdjustmentTab upliftPercent={0} setUpliftPercent={vi.fn()} />)
    expect(screen.queryByText(/Promo uplift/)).not.toBeInTheDocument()
  })

  it('calls setUpliftPercent clamped between 0 and 100 on input change', async () => {
    const set = vi.fn()
    render(<ForecastAdjustmentTab upliftPercent={10} setUpliftPercent={set} />)
    const input = screen.getByRole('spinbutton')
    await userEvent.clear(input)
    await userEvent.type(input, '50')
    expect(set).toHaveBeenCalled()
  })

  it('disables Reset Uplift when uplift is 0', () => {
    render(<ForecastAdjustmentTab upliftPercent={0} setUpliftPercent={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Reset Uplift' })).toBeDisabled()
  })

  it('enables Reset Uplift when uplift > 0 and calls setUpliftPercent(0) on click', async () => {
    const set = vi.fn()
    render(<ForecastAdjustmentTab upliftPercent={15} setUpliftPercent={set} />)
    const btn = screen.getByRole('button', { name: 'Reset Uplift' })
    expect(btn).toBeEnabled()
    await userEvent.click(btn)
    expect(set).toHaveBeenCalledWith(0)
  })
})
