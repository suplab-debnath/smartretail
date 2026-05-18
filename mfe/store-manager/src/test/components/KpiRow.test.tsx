import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { KpiRow } from '../../components/KpiRow'
import type { AlertKpi } from '../../types'

const alertKpi: AlertKpi = {
  criticalCount: 2,
  highCount: 4,
  mediumCount: 6,
  totalActive: 12,
}

describe('KpiRow', () => {
  it('renders all four KPI card labels', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText('Low Stock Alerts')).toBeInTheDocument()
    expect(screen.getByText('On-Hand Units')).toBeInTheDocument()
    expect(screen.getByText('Pending Replenishment Orders')).toBeInTheDocument()
    expect(screen.getByText('Forecast Coverage')).toBeInTheDocument()
  })

  it('shows total active alert count', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText('12')).toBeInTheDocument()
  })

  it('shows alert severity sub-items', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText('CRITICAL: 2')).toBeInTheDocument()
    expect(screen.getByText('HIGH: 4')).toBeInTheDocument()
    expect(screen.getByText('MEDIUM: 6')).toBeInTheDocument()
  })

  it('formats on-hand units with locale separator', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText((50000).toLocaleString())).toBeInTheDocument()
  })

  it('shows pending replenishment count', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('formats forecast coverage to one decimal place', () => {
    render(
      <KpiRow
        alertKpi={alertKpi}
        totalOnHandUnits={50000}
        pendingReplenishmentCount={7}
        forecastCoveragePct={85.5}
      />
    )
    expect(screen.getByText('85.5%')).toBeInTheDocument()
  })
})
