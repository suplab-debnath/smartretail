import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import MfeRevealPanel from '../../components/MfeRevealPanel'
import type { MfeReveal } from '../../types'

const reveal: MfeReveal = {
  mfe: 'store-manager',
  localPort: 5173,
  path: '/dashboard',
  label: 'Store Manager Dashboard',
}

function mockEnvFetch(mfeUrls?: Record<string, string>) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    json: () => Promise.resolve({ env: 'aws', mfeUrls: mfeUrls ?? {} }),
  }))
}

function mockEnvFetchFailure() {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('no server')))
}

beforeEach(() => {
  mockEnvFetchFailure()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MfeRevealPanel', () => {
  it('renders the label', () => {
    render(<MfeRevealPanel reveal={reveal} />)
    expect(screen.getByText('Store Manager Dashboard')).toBeInTheDocument()
  })

  it('renders the port number in local mode', () => {
    render(<MfeRevealPanel reveal={reveal} />)
    expect(screen.getByText(':5173')).toBeInTheDocument()
  })

  it('renders a link to the MFE in a new tab using localhost fallback', () => {
    render(<MfeRevealPanel reveal={reveal} />)
    const link = screen.getByRole('link', { name: /new tab/ })
    expect(link).toHaveAttribute('href', 'http://localhost:5173/dashboard')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('shows ⊞ expand button initially', () => {
    render(<MfeRevealPanel reveal={reveal} />)
    expect(screen.getByText(/⊞ expand/)).toBeInTheDocument()
  })

  it('shows ⊟ collapse after clicking expand', async () => {
    render(<MfeRevealPanel reveal={reveal} />)
    await userEvent.click(screen.getByRole('button', { name: /expand/ }))
    expect(screen.getByText(/⊟ collapse/)).toBeInTheDocument()
  })

  it('renders iframe with localhost src as fallback', () => {
    render(<MfeRevealPanel reveal={reveal} />)
    const iframe = screen.getByTitle('Store Manager Dashboard')
    expect(iframe).toHaveAttribute('src', 'http://localhost:5173/dashboard')
  })

  it('renders sc-planner mfe variant with teal border class', () => {
    render(<MfeRevealPanel reveal={{ ...reveal, mfe: 'sc-planner', localPort: 5174, label: 'SC Planner' }} />)
    expect(screen.getByText('SC Planner')).toBeInTheDocument()
  })

  it('renders executive mfe variant', () => {
    render(<MfeRevealPanel reveal={{ ...reveal, mfe: 'executive', localPort: 5175, label: 'Executive' }} />)
    expect(screen.getByText('Executive')).toBeInTheDocument()
    expect(screen.getByText(':5175')).toBeInTheDocument()
  })

  it('uses CloudFront URL from /api/env when available', async () => {
    mockEnvFetch({ storeManager: 'https://abc123.cloudfront.net' })
    render(<MfeRevealPanel reveal={reveal} />)
    await waitFor(() => {
      expect(screen.getByTitle('Store Manager Dashboard'))
        .toHaveAttribute('src', 'https://abc123.cloudfront.net/dashboard')
    })
    expect(screen.getByRole('link', { name: /new tab/ }))
      .toHaveAttribute('href', 'https://abc123.cloudfront.net/dashboard')
  })

  it('shows CloudFront hostname label in AWS mode', async () => {
    mockEnvFetch({ storeManager: 'https://abc123.cloudfront.net' })
    render(<MfeRevealPanel reveal={reveal} />)
    await waitFor(() => {
      expect(screen.getByText('abc123.cloudfront.net')).toBeInTheDocument()
    })
  })

  it('falls back to localhost when /api/env returns no URL for the MFE', async () => {
    mockEnvFetch({ executive: 'https://xyz.cloudfront.net' })
    render(<MfeRevealPanel reveal={reveal} />)
    await waitFor(() => {
      expect(screen.getByTitle('Store Manager Dashboard'))
        .toHaveAttribute('src', 'http://localhost:5173/dashboard')
    })
  })
})
