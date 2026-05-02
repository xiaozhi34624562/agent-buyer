import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import App from '../App'

describe('App', () => {
  it('should render page title Agent Buyer Console', () => {
    render(<App />)
    expect(screen.getByText('Agent Buyer Console')).toBeInTheDocument()
  })
})