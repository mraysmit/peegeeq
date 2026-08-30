import { test, expect, type Page } from '@playwright/test'

type RouteContract = {
  name: string
  path: string
  navTestId: string
  selectedLabel: string
  rootTestId: string
}

const routes: RouteContract[] = [
  { name: 'Overview', path: '/', navTestId: 'nav-overview', selectedLabel: 'Overview', rootTestId: 'overview-page' },
  { name: 'Tools', path: '/tools', navTestId: 'nav-tools', selectedLabel: 'Tools', rootTestId: 'tools-page' },
  { name: 'Setups', path: '/setups', navTestId: 'nav-setups', selectedLabel: 'Setups', rootTestId: 'setups-page' },
  { name: 'Message Generator', path: '/generator', navTestId: 'nav-generator', selectedLabel: 'Message Generator', rootTestId: 'generator-page' },
  { name: 'Scheduled Runs', path: '/generator/schedules', navTestId: 'nav-generator-schedules', selectedLabel: 'Scheduled Runs', rootTestId: 'scheduled-runs-page' },
  { name: 'Templates', path: '/generator/templates', navTestId: 'nav-generator-templates', selectedLabel: 'Templates', rootTestId: 'template-manager-page' },
  { name: 'Value Lists', path: '/generator/value-lists', navTestId: 'nav-generator-value-lists', selectedLabel: 'Value Lists', rootTestId: 'value-list-manager-page' },
]

async function openRoute(page: Page, route: RouteContract) {
  await page.goto(route.path)
  await expect(page.getByTestId(route.rootTestId)).toBeVisible()
}

test.describe('Application shell route contracts', () => {
  for (const route of routes) {
    test(`${route.name}: renders its routed page root`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.getByTestId(route.rootTestId)).toBeVisible()
    })

    test(`${route.name}: retains the application layout and sidebar`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.getByTestId('app-layout')).toBeVisible()
      await expect(page.getByTestId('app-sidebar')).toBeVisible()
    })

    test(`${route.name}: retains the PeeGeeQ Utilities brand`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.getByTestId('app-logo')).toHaveText('PeeGeeQ Utilities')
    })

    test(`${route.name}: exposes the complete seven-destination menu`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.getByTestId('app-sidebar').locator('a[data-testid^="nav-"]')).toHaveCount(7)
    })

    test(`${route.name}: exposes its canonical navigation href`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.getByTestId(route.navTestId)).toHaveAttribute('href', route.path)
    })

    test(`${route.name}: marks its menu entry as selected`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.locator('.ant-menu-item-selected')).toHaveText(route.selectedLabel)
    })

    test(`${route.name}: renders its route inside the main content region`, async ({ page }) => {
      await openRoute(page, route)
      await expect(page.locator('main').getByTestId(route.rootTestId)).toBeVisible()
    })
  }
})

test.describe('Overview behavior contracts', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await expect(page.getByTestId('overview-page')).toBeVisible()
  })

  test('renders the Overview page root', async ({ page }) => {
    await expect(page.getByTestId('overview-page')).toBeVisible()
  })

  test('renders the exact System Overview heading', async ({ page }) => {
    await expect(page.getByRole('heading', { level: 2, name: 'System Overview' })).toBeVisible()
  })

  test('retains the PeeGeeQ Utilities document title', async ({ page }) => {
    await expect(page).toHaveTitle('PeeGeeQ Utilities')
  })

  test('renders the Setups card heading', async ({ page }) => {
    await expect(page.locator('.ant-card-head-title').filter({ hasText: /^Setups$/ })).toBeVisible()
  })

  test('shows the Refresh action', async ({ page }) => {
    await expect(page.getByTestId('refresh-button')).toBeVisible()
  })

  test('enables Refresh after the initial request settles', async ({ page }) => {
    await expect(page.getByTestId('refresh-button')).toBeEnabled()
  })

  test('labels the Refresh action explicitly', async ({ page }) => {
    await expect(page.getByTestId('refresh-button')).toHaveText('Refresh')
  })

  test('shows the reload icon on Refresh', async ({ page }) => {
    await expect(page.getByTestId('refresh-button').locator('.anticon-reload')).toBeVisible()
  })

  test('exposes Refresh as an accessible button', async ({ page }) => {
    await expect(page.getByRole('button', { name: 'Refresh' })).toBeVisible()
  })

  test('shows the Connect setup action', async ({ page }) => {
    await expect(page.getByTestId('connect-setup-button')).toBeVisible()
  })

  test('enables Connect setup', async ({ page }) => {
    await expect(page.getByTestId('connect-setup-button')).toBeEnabled()
  })

  test('labels Connect setup explicitly', async ({ page }) => {
    await expect(page.getByTestId('connect-setup-button')).toHaveText('Connect setup')
  })

  test('shows the plus icon on Connect setup', async ({ page }) => {
    await expect(page.getByTestId('connect-setup-button').locator('.anticon-plus')).toBeVisible()
  })

  test('styles Connect setup as the primary action', async ({ page }) => {
    await expect(page.getByTestId('connect-setup-button')).toHaveClass(/ant-btn-primary/)
  })

  test('marks Overview as the active navigation destination', async ({ page }) => {
    await expect(page.locator('.ant-menu-item-selected')).toHaveText('Overview')
  })

  test('settles into either a setup list or the documented empty state', async ({ page }) => {
    await expect(page.getByTestId('setups-list').or(page.getByTestId('no-setups'))).toBeVisible()
  })

  test('never displays both the setup list and empty state together', async ({ page }) => {
    await expect.poll(async () =>
      await page.getByTestId('setups-list').count() + await page.getByTestId('no-setups').count()
    ).toBe(1)
  })

  test('keeps Overview usable after a manual refresh', async ({ page }) => {
    await page.getByTestId('refresh-button').click()
    await expect(page.getByTestId('refresh-button')).toBeEnabled()
    await expect(page.getByRole('heading', { name: 'System Overview' })).toBeVisible()
  })

  test('supports consecutive manual refreshes', async ({ page }) => {
    const refresh = page.getByTestId('refresh-button')
    await refresh.click()
    await expect(refresh).toBeEnabled()
    await refresh.click()
    await expect(refresh).toBeEnabled()
  })

  test('opens the connect form with the primary action', async ({ page }) => {
    await page.getByTestId('connect-setup-button').click()
    await expect(page).toHaveURL(/\/setups\/connect$/)
    await expect(page.getByTestId('connect-setup-page')).toBeVisible()
  })

  test('opens the connect form from the keyboard', async ({ page }) => {
    const connect = page.getByTestId('connect-setup-button')
    await connect.focus()
    await connect.press('Enter')
    await expect(page).toHaveURL(/\/setups\/connect$/)
  })
})
