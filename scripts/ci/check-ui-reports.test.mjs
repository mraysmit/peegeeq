import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { expectedReports, verifyReports } from './check-ui-reports.mjs'

const modules = ['peegeeq-api', 'peegeeq-management-ui', 'peegeeq-utilities-ui']
test('full UI gate requires four distinct reports', () => {
  const reports = expectedReports('all', 'beginning', modules)
  assert.equal(reports.length, 4)
  assert.equal(new Set(reports).size, 4)
})
test('resuming utilities excludes management reports', () => {
  const reports = expectedReports('all', 'peegeeq-utilities-ui', modules)
  assert.equal(reports.length, 2)
  assert.ok(reports.every(path => path.startsWith('peegeeq-utilities-ui/')))
})
test('core requires unit reports only', () => {
  assert.deepEqual(expectedReports('core', 'beginning', modules), [
    'peegeeq-management-ui/target/ui-reports/vitest.xml',
    'peegeeq-utilities-ui/target/ui-reports/vitest.xml',
  ])
})
test('Java-only reactors do not require UI reports', () => {
  assert.deepEqual(expectedReports('all', 'beginning', ['peegeeq-api']), [])
})
test('unknown selection fails explicitly', () => {
  assert.throws(() => expectedReports('wrong', 'beginning', modules), /Unknown suite/)
  assert.throws(() => expectedReports('all', 'wrong', modules), /Unknown start module/)
})
test('missing, empty, and failed reports cannot pass validation', t => {
  const root = mkdtempSync(join(tmpdir(), 'peegeeq-report-contract-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const report = 'peegeeq-management-ui/target/ui-reports/vitest.xml'
  assert.throws(() => verifyReports(root, [report]), /Missing/)
  mkdirSync(dirname(join(root, report)), { recursive: true })
  writeFileSync(join(root, report), '')
  assert.throws(() => verifyReports(root, [report]), /Empty|Invalid/)
  writeFileSync(join(root, report), '<testsuites tests="1" failures="1" errors="0" skipped="0"></testsuites>')
  assert.throws(() => verifyReports(root, [report]), /failed/)
})
test('valid report counts are returned for reconciliation', t => {
  const root = mkdtempSync(join(tmpdir(), 'peegeeq-report-contract-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  writeFileSync(join(root, 'report.xml'), '<testsuites tests="3" failures="0" errors="0" skipped="1"></testsuites>')
  assert.deepEqual(verifyReports(root, ['report.xml']), [
    { path: 'report.xml', tests: 3, failures: 0, errors: 0, skipped: 1 },
  ])
})
