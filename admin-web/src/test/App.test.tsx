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
})