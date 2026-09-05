import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const root = fileURLToPath(new URL('../../', import.meta.url))
for (const module of ['peegeeq-management-ui', 'peegeeq-utilities-ui']) {
  for (const runner of ['vitest', 'playwright']) {
    test(module + ' ' + runner + ' publishes genuine pass/fail counts', { timeout: 120000 }, t => {
      const cwd = resolve(root, module)
      const report = resolve(cwd, 'target/ui-reports', runner + '.xml')
      t.after(() => rmSync(report, { force: true }))
      for (const fail of ['0', '1']) {
        rmSync(report, { force: true })
        const cli = runner === 'vitest' ? 'node_modules/vitest/vitest.mjs'
          : 'node_modules/@playwright/test/cli.js'
        const result = spawnSync(process.execPath, [cli, runner === 'vitest' ? 'run' : 'test',
          '--config', runner === 'vitest' ? 'scripts/reporting/vitest.config.ts' : 'playwright.reporting.config.ts'], {
          cwd, encoding: 'utf8', timeout: 60000,
          env: { ...process.env, CI: 'true', PLAYWRIGHT_HTML_OPEN: 'never', PEEGEEQ_REPORT_CONTRACT_FAIL: fail },
        })
        if (result.error) throw result.error
        assert.equal(result.status, Number(fail), result.stdout + result.stderr)
        const xml = readFileSync(report, 'utf8')
        assert.match(xml, /<testsuites\b[^>]*\btests="1"/)
        assert.match(xml, new RegExp('<testsuites\\b[^>]*\\bfailures="' + fail + '"'))
        assert.equal((xml.match(/<testcase\b/g) ?? []).length, 1)
        assert.match(xml, /preserves the real outcome/)
      }
    })
  }
}
