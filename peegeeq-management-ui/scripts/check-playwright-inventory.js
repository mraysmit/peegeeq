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

function inspectTests(config) {
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
  if (!match) throw new Error(`Could not find the Playwright total for ${config}`)

  const identities = []
  for (const line of output.split(/\r?\n/)) {
    const listed = line.match(/^\s+\[[^\]]+\]\s+›\s+(.+?\.spec\.ts:\d+:\d+)\s+›\s+(.+)$/)
    if (listed) identities.push(`${listed[1]} › ${listed[2]}`)
  }

  const executions = Number(match[1])
  if (identities.length !== executions) {
    throw new Error(
      `Parsed ${identities.length} listed cases from ${config}, but Playwright reported ${executions}`
    )
  }

  const unique = new Set(identities)
  const duplicates = [...unique].filter(
    (identity) => identities.filter((candidate) => candidate === identity).length > 1
  )
  return { executions, unique, duplicates }
}

const inventories = [
  ['functional', 'playwright.config.ts'],
  ['screenshots', 'playwright.screenshots.config.ts'],
]

let failed = false
let total = 0
const inspected = new Map()
for (const [name, config] of inventories) {
  const inventory = inspectTests(config)
  inspected.set(name, inventory)
  const actual = inventory.unique.size
  const target = expected.get(name)
  total += actual
  console.log(
    `${name}: ${actual} unique (${inventory.executions} executions)`
    + `${target === undefined ? '' : ` (expected ${target})`}`
  )
  if (inventory.duplicates.length > 0) {
    console.error(`${name}: duplicate project-expanded cases:\n${inventory.duplicates.join('\n')}`)
    failed = true
  }
  if (target !== undefined && actual !== target) failed = true
}

const functional = inspected.get('functional')?.unique ?? new Set()
const screenshots = inspected.get('screenshots')?.unique ?? new Set()
const overlap = [...functional].filter((identity) => screenshots.has(identity))
if (overlap.length > 0) {
  console.error(`functional/screenshot overlap:\n${overlap.join('\n')}`)
  failed = true
}

console.log(`total unique: ${total}`)

if (failed) process.exitCode = 1
