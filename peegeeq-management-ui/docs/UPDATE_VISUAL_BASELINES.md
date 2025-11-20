# Updating Visual Regression Test Baselines

This guide explains how to update visual regression test baselines for the PeeGeeQ Management UI.

## When to Update Baselines

Update visual regression baselines when:

1. **Intentional UI changes** - You've made deliberate changes to component styling, layout, or appearance
2. **Content changes** - Text, images, or data format changes affect rendering
3. **Dependencies updated** - Ant Design or other UI library versions changed
4. **Font rendering differences** - Running tests on different OS or browser versions
5. **Data structure changes** - Backend API responses changed format

## Prerequisites

Before updating baselines:

1. **Backend server running** - Ensure REST API is available on `http://localhost:8080`
   ```powershell
   cd peegeeq-rest
   mvn clean compile exec:java
   ```

2. **UI dev server running** - Ensure frontend is available on `http://localhost:3000`
   ```powershell
   cd peegeeq-management-ui
   npm run dev
   ```

3. **Review changes** - Ensure the visual changes are intentional and correct

## Update All Visual Baselines

To update all visual regression test baselines:

```powershell
cd peegeeq-management-ui
npx playwright test visual-regression --update-snapshots
```

## Update Specific Test Baselines

To update baselines for specific tests only:

```powershell
# Update only layout tests
npx playwright test visual-regression --update-snapshots --grep "Layout and Navigation"

# Update only page-specific tests
npx playwright test visual-regression --update-snapshots --grep "Page-specific"

# Update only modal tests
npx playwright test visual-regression --update-snapshots --grep "Modal and Component"

# Update only responsive design tests
npx playwright test visual-regression --update-snapshots --grep "Responsive Design"

# Update only data table tests
npx playwright test visual-regression --update-snapshots --grep "Data Table"
```

## Update for Specific Browser

```powershell
# Update only Chromium baselines
npx playwright test visual-regression --update-snapshots --project=chromium

# Update only Firefox baselines
npx playwright test visual-regression --update-snapshots --project=firefox

# Update only WebKit baselines
npx playwright test visual-regression --update-snapshots --project=webkit
```

## Verify Updated Baselines

After updating, run tests without `--update-snapshots` to verify:

```powershell
npx playwright test visual-regression --project=chromium
```

## Review Changes Before Committing

1. **Review diff images** - Check the visual differences in `test-results/` directory
2. **Inspect new snapshots** - Verify new baselines in `src/tests/e2e/visual-regression.spec.ts-snapshots/`
3. **Run full test suite** - Ensure other tests still pass
4. **Commit with description** - Document why baselines were updated

```powershell
git add src/tests/e2e/visual-regression.spec.ts-snapshots/
git commit -m "test: Update visual regression baselines after [reason]"
```

## Troubleshooting

### Baselines Still Failing After Update

- **Clear test results**: `Remove-Item -Recurse -Force test-results`
- **Check data consistency**: Ensure backend returns consistent data
- **Verify server state**: Restart both backend and frontend servers
- **Check viewport size**: Ensure consistent browser window size

### Different Results on Different Machines

- **Platform differences**: Generate separate baselines per OS (e.g., `-win32`, `-darwin`, `-linux`)
- **Font rendering**: Visual differences due to different font rendering engines
- **Screen resolution**: High DPI displays may produce different snapshots

### Too Many Failures

If many tests fail after updating:

1. **Regenerate all baselines** at once instead of selectively
2. **Review tolerance settings** in `playwright.config.ts`
3. **Consider increasing** `maxDiffPixels` or `threshold` for less strict comparison

## Configuration

Visual regression settings are in `playwright.config.ts`:

```typescript
expect: {
  toHaveScreenshot: {
    maxDiffPixels: 100,    // Max different pixels allowed
    threshold: 0.2,         // 0-1, higher = more tolerance
  },
}
```

Adjust these values if tests are too strict or too lenient.
