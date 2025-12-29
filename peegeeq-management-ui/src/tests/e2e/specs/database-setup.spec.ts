import { test, expect } from '../page-objects'
import * as fs from 'fs'
import { SETUP_ID } from '../test-constants'

/**
 * Database Setup Tests
 *
 * Tests database setup creation and management.
 * These tests run sequentially to ensure proper setup state.
 */

test.describe.configure({ mode: 'serial' })

test.describe('Database Setup', () => {

  test.beforeEach(async ({ page }) => {
    // Capture console errors for debugging
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.error('❌ Browser console error:', msg.text())
      }
    })
    page.on('pageerror', error => {
      console.error('❌ Page error:', error.message)
    })
  })

  test.describe('Setup Creation', () => {

    test('should create database setup through UI', async ({ page, databaseSetupsPage }) => {
      // Read TestContainers connection details
      const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))

      await page.goto('/')
      await databaseSetupsPage.goto()

      // Verify we're on the database setups page
      expect(page.url()).toContain('/database-setups')

      // Create setup through UI
      await databaseSetupsPage.createSetup({
        setupId: SETUP_ID,
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName: `e2e_test_db_${Date.now()}`,
        username: dbConfig.username,
        password: dbConfig.password,
        schema: 'public'
      })

      // Verify setup was created
      const exists = await databaseSetupsPage.setupExists(SETUP_ID)
      expect(exists).toBeTruthy()

      // Verify setup appears in table
      const setupCount = await databaseSetupsPage.getSetupCount()
      expect(setupCount).toBeGreaterThanOrEqual(1)
    })

    test('should display created setup in table', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()

      // Setup should exist from previous test
      const exists = await databaseSetupsPage.setupExists(SETUP_ID)
      expect(exists).toBeTruthy()

      // Table should show the setup
      await expect(databaseSetupsPage.getSetupsTable()).toBeVisible()
      await expect(page.locator(`tr:has-text("${SETUP_ID}")`)).toBeVisible()
    })
  })

  test.describe('Setup Management', () => {

    test('should navigate to database setups page', async ({ page, basePage }) => {
      await page.goto('/')
      await basePage.navigateTo('database-setups')
      expect(page.url()).toContain('/database-setups')
    })

    test('should display database setups table', async ({ page, databaseSetupsPage }) => {
      await page.goto('/')
      await databaseSetupsPage.goto()

      // Table should be visible
      await expect(databaseSetupsPage.getSetupsTable()).toBeVisible()
    })
  })
})

