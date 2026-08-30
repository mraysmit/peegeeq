import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const playwrightCli = fileURLToPath(new URL('../node_modules/@playwright/test/cli.js', import.meta.url))

const expected = new Map()
for (let index = 2; index < process.argv.length; index += 2) {
  const option = process.argv[index]
  const value = process.argv[index + 1]
  if (!option?.startsWith('--') || value === undefined || !/^\d+$/.test(value)) {
    throw new Error(`Expected numeric option/value pairs, received: ${process.argv.slice(2).join(' ')}`)
  }
  expected.set(option.slice(2), Number(value))
}

function countTests(config) {
  const result = spawnSync(process.execPath, [playwrightCli, 'test', '--config', config, '--list'], {
    encoding: 'utf8',
    env: { ...process.env, NO_COLOR: '1' },
  })

  if (result.error) throw result.error
  if (result.status !== 0) {
    process.stderr.write(result.stdout)
    process.stderr.write(result.stderr)
    throw new Error(`Playwright inventory failed for ${config} with exit code ${result.status}`)
  }

  const output = `${result.stdout}\n${result.stderr}`
  const match = output.match(/Total:\s+(\d+)\s+tests?\b/)
  if (!match) {
    throw new Error(`Could not find the Playwright total for ${config}`)
  }
  return Number(match[1])
}

const inventories = [
  ['functional', 'playwright.config.ts'],
  ['screenshots', 'playwright.screenshots.config.ts'],
]

let failed = false
let total = 0
for (const [name, config] of inventories) {
  const actual = countTests(config)
  const target = expected.get(name)
  total += actual
  console.log(`${name}: ${actual}${target === undefined ? '' : ` (expected ${target})`}`)
  if (target !== undefined && actual !== target) failed = true
}
console.log(`total: ${total}`)

if (failed) {
  process.exitCode = 1
}
