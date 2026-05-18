import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { DemandForecastTab } from '../../components/DemandForecastTab'
import { useForecast } from '../../hooks/useForecast'
import type { ForecastDataResponse } from '../../types'

vi.mock('../../hooks/useForecast')
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  ComposedChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Area: () => null,
  Line: () => null,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  Legend: () => null,
}))

const mockedHook = vi.mocked(useForecast)

const mockData: ForecastDataResponse = {
  skuId: 'SKU-BEV-001', dcId: 'DC-LONDON', horizonDays: 30, latestMape: 0.08,
  bands: [{ forecastDate: '2026-05-18', p10: 80, p50: 100, p90: 120, actualUnits: 95 }],
  dataFreshness: '2026-05-18T00:00:00Z',
}

beforeEach(() => vi.clearAllMocks())

describe('DemandForecastTab', () => {
  it('shows loading state', () => {
    mockedHook.mockReturnValue({ data: null, loading: true, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    expect(screen.getByText('Loading forecast…')).toBeInTheDocument()
  })

  it('shows error state', () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: 'HTTP 404' })
    render(<DemandForecastTab upliftPercent={0} />)
    expect(screen.getByText(/Error loading forecast: HTTP 404/)).toBeInTheDocument()
  })

  it('renders chart and MAPE badge when data loaded', () => {
    mockedHook.mockReturnValue({ data: mockData, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    expect(screen.getByText(/SKU-BEV-001/)).toBeInTheDocument()
    expect(screen.getByText(/MAPE: 8.0%/)).toBeInTheDocument()
  })

  it('shows green MAPE badge when mape < 10%', () => {
    mockedHook.mockReturnValue({ data: { ...mockData, latestMape: 0.08 }, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    const badge = screen.getByText(/MAPE: 8.0%/)
    expect(badge.className).toContain('text-green-700')
  })

  it('shows amber MAPE badge when 10% <= mape <= 20%', () => {
    mockedHook.mockReturnValue({ data: { ...mockData, latestMape: 0.15 }, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    const badge = screen.getByText(/MAPE: 15.0%/)
    expect(badge.className).toContain('text-amber-700')
  })

  it('shows red MAPE badge when mape > 20%', () => {
    mockedHook.mockReturnValue({ data: { ...mockData, latestMape: 0.25 }, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    const badge = screen.getByText(/MAPE: 25.0%/)
    expect(badge.className).toContain('text-red-700')
  })

  it('changes horizon on button click', async () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    await userEvent.click(screen.getByRole('button', { name: '7d' }))
    expect(mockedHook).toHaveBeenCalledWith(expect.any(String), expect.any(String), 7)
  })

  it('changes DC via select', async () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    await userEvent.selectOptions(screen.getByRole('combobox'), 'DC-MANCHESTER')
    expect(mockedHook).toHaveBeenCalledWith(expect.any(String), 'DC-MANCHESTER', expect.any(Number))
  })

  it('applies SKU on Enter key in text input', async () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    const input = screen.getByRole('textbox')
    await userEvent.clear(input)
    await userEvent.type(input, 'SKU-NEW{Enter}')
    expect(mockedHook).toHaveBeenCalledWith('SKU-NEW', expect.any(String), expect.any(Number))
  })

  it('falls back to default SKU when input is blank on blur', async () => {
    mockedHook.mockReturnValue({ data: null, loading: false, error: null })
    render(<DemandForecastTab upliftPercent={0} />)
    const input = screen.getByRole('textbox')
    await userEvent.clear(input)
    await userEvent.tab()
    expect(mockedHook).toHaveBeenCalledWith('SKU-BEV-001', expect.any(String), expect.any(Number))
  })
})
