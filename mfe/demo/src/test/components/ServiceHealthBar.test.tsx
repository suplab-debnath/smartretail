import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import ServiceHealthBar from '../../components/ServiceHealthBar'
import type { HealthStatus } from '../../types'

const allOk: HealthStatus = { sis: 'ok', ims: 'ok', re: 'ok', ars: 'ok', dfs: 'ok', sup: 'ok' }
const allDown: HealthStatus = { sis: 'down', ims: 'down', re: 'down', ars: 'down', dfs: 'down', sup: 'down' }

describe('ServiceHealthBar', () => {
  it('renders all 6 service labels', () => {
    render(<ServiceHealthBar health={allOk} />)
    for (const label of ['SIS', 'IMS', 'RE', 'ARS', 'DFS', 'SUP']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
  })

  it('shows emerald color for healthy services', () => {
    render(<ServiceHealthBar health={allOk} />)
    const sisLabel = screen.getByText('SIS')
    expect(sisLabel.className).toContain('text-emerald-400')
  })

  it('shows red color for down services', () => {
    render(<ServiceHealthBar health={allDown} />)
    const sisLabel = screen.getByText('SIS')
    expect(sisLabel.className).toContain('text-red-500')
  })

  it('shows mixed status correctly', () => {
    const mixed: HealthStatus = { ...allOk, ims: 'down' }
    render(<ServiceHealthBar health={mixed} />)
    expect(screen.getByText('SIS').className).toContain('text-emerald-400')
    expect(screen.getByText('IMS').className).toContain('text-red-500')
  })
})
