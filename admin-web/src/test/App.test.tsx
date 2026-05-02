import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import App from '../App'

describe('App', () => {
  it('should render page title Agent Buyer Console', () => {
    render(<App />)
    expect(screen.getByText('Agent Buyer Console')).toBeInTheDocument()
  })

  it('should render Runs panel', () => {
    render(<App />)
    expect(screen.getByText('Runs')).toBeInTheDocument()
  })

  it('should render Timeline panel', () => {
    render(<App />)
    expect(screen.getByText('Timeline')).toBeInTheDocument()
  })

  it('should render Chat panel', () => {
    render(<App />)
    expect(screen.getByText('Chat')).toBeInTheDocument()
  })

  it('should display user ID from localStorage', () => {
    render(<App />)
    expect(screen.getByText(/User:/)).toBeInTheDocument()
  })

  it('should not display adminToken in page text', () => {
    localStorage.setItem('adminToken', 'secret-admin-token-12345')
    render(<App />)
    // Admin token should never appear as plain text in the rendered page
    expect(screen.queryByText('secret-admin-token-12345')).not.toBeInTheDocument()
    // The token input is password type, so its value is not visible
    expect(screen.queryByDisplayValue('secret-admin-token-12345')).not.toBeInTheDocument()
  })

  it('should render Debug toggle button', () => {
    render(<App />)
    expect(screen.getByRole('button', { name: /Debug/ })).toBeInTheDocument()
  })

  it('should render Settings button', () => {
    render(<App />)
    expect(screen.getByRole('button', { name: 'Settings' })).toBeInTheDocument()
  })
})