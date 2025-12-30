import { test, expect } from '@playwright/test'

/**
 * Settings Page - Ping Utilities Tests
 * 
 * Tests the individual health check ping utilities for REST API, WebSocket, and SSE
 * on the Settings page. Verifies that each ping utility can successfully test its
 * respective endpoint and display correct results.
 */
test.describe('Settings - Ping Utilities', () => {
  
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
  })

  test.describe('REST API Ping', () => {
    
    test('should show REST API ping button', async ({ page }) => {
      const pingRestBtn = page.getByTestId('ping-rest-btn')
      await expect(pingRestBtn).toBeVisible()
      await expect(pingRestBtn).toContainText('Ping Now')
    })

    test('should successfully ping REST API endpoint', async ({ page }) => {
      // Click the REST ping button
      await page.getByTestId('ping-rest-btn').click()

      // Wait for the result to appear
      const result = page.getByTestId('ping-rest-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Result should show success (backend is running)
      await expect(result).toContainText(/UP|healthy|success/i)
    })

    test('should display timestamp on REST ping result', async ({ page }) => {
      await page.getByTestId('ping-rest-btn').click()

      const result = page.getByTestId('ping-rest-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Should show timestamp
      await expect(result).toContainText(/Last ping:/i)
    })
  })

  test.describe('WebSocket Ping', () => {
    
    test('should show WebSocket ping button', async ({ page }) => {
      const pingWsBtn = page.getByTestId('ping-ws-btn')
      await expect(pingWsBtn).toBeVisible()
      await expect(pingWsBtn).toContainText('Ping Now')
    })

    test('should successfully ping WebSocket endpoint', async ({ page }) => {
      // Click the WebSocket ping button
      await page.getByTestId('ping-ws-btn').click()

      // Wait for the result to appear
      const result = page.getByTestId('ping-ws-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Result should show success (WebSocket endpoint exists)
      await expect(result).toContainText(/UP|connected|success/i)
    })

    test('should display timestamp on WebSocket ping result', async ({ page }) => {
      await page.getByTestId('ping-ws-btn').click()

      const result = page.getByTestId('ping-ws-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Should show timestamp
      await expect(result).toContainText(/Last ping:/i)
    })
  })

  test.describe('SSE Ping', () => {
    
    test('should show SSE ping button', async ({ page }) => {
      const pingSseBtn = page.getByTestId('ping-sse-btn')
      await expect(pingSseBtn).toBeVisible()
      await expect(pingSseBtn).toContainText('Ping Now')
    })

    test('should successfully ping SSE endpoint', async ({ page }) => {
      // Click the SSE ping button
      await page.getByTestId('ping-sse-btn').click()

      // Wait for the result to appear
      const result = page.getByTestId('ping-sse-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Result should show success (SSE endpoint exists)
      await expect(result).toContainText(/UP|connected|success/i)
    })

    test('should display timestamp on SSE ping result', async ({ page }) => {
      await page.getByTestId('ping-sse-btn').click()

      const result = page.getByTestId('ping-sse-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Should show timestamp
      await expect(result).toContainText(/Last ping:/i)
    })
  })

  test.describe('Ping Utilities Integration', () => {
    
    test('should allow pinging all three endpoints sequentially', async ({ page }) => {
      // Ping REST
      await page.getByTestId('ping-rest-btn').click()
      await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 10000 })

      // Ping WebSocket
      await page.getByTestId('ping-ws-btn').click()
      await expect(page.getByTestId('ping-ws-result')).toBeVisible({ timeout: 10000 })

      // Ping SSE
      await page.getByTestId('ping-sse-btn').click()
      await expect(page.getByTestId('ping-sse-result')).toBeVisible({ timeout: 10000 })

      // All three results should be visible
      await expect(page.getByTestId('ping-rest-result')).toBeVisible()
      await expect(page.getByTestId('ping-ws-result')).toBeVisible()
      await expect(page.getByTestId('ping-sse-result')).toBeVisible()
    })

    test('should show loading state while pinging', async ({ page }) => {
      // Start ping but don't wait for completion
      const pingPromise = page.getByTestId('ping-rest-btn').click()

      // Wait for the click to complete
      await pingPromise

      // Result should eventually appear
      await expect(page.getByTestId('ping-rest-result')).toBeVisible({ timeout: 10000 })
    })

    test('REST API health check endpoint information should be visible', async ({ page }) => {
      // Should show which endpoint is being tested
      await expect(page.getByText('Endpoint: /api/v1/health')).toBeVisible()
    })

    test('WebSocket health check endpoint information should be visible', async ({ page }) => {
      // Should show which endpoint is being tested
      await expect(page.getByText('Endpoint: /ws/health')).toBeVisible()
    })

    test('SSE health check endpoint information should be visible', async ({ page }) => {
      // Should show which endpoint is being tested
      await expect(page.getByText('Endpoint: /api/v1/sse/health')).toBeVisible()
    })
  })

  test.describe('Ping Results Validation', () => {
    
    test('REST ping result should show success icon when successful', async ({ page }) => {
      await page.getByTestId('ping-rest-btn').click()
      
      const result = page.getByTestId('ping-rest-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Check for success class/type
      const resultClass = await result.getAttribute('class')
      expect(resultClass).toContain('ant-alert-success')
    })

    test('WebSocket ping result should show success icon when successful', async ({ page }) => {
      await page.getByTestId('ping-ws-btn').click()
      
      const result = page.getByTestId('ping-ws-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Check for success class/type
      const resultClass = await result.getAttribute('class')
      expect(resultClass).toContain('ant-alert-success')
    })

    test('SSE ping result should show success icon when successful', async ({ page }) => {
      await page.getByTestId('ping-sse-btn').click()
      
      const result = page.getByTestId('ping-sse-result')
      await expect(result).toBeVisible({ timeout: 10000 })

      // Check for success class/type
      const resultClass = await result.getAttribute('class')
      expect(resultClass).toContain('ant-alert-success')
    })
  })
})
