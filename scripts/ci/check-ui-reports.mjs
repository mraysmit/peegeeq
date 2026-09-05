import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const frontends = ['peegeeq-management-ui', 'peegeeq-utilities-ui']

export function expectedReports(suite, start, modules) {
  if (!['core', 'smoke', 'integration', 'untagged', 'all'].includes(suite)) throw new Error('Unknown suite: ' + suite)
  const first = suite !== 'all' || start === 'beginning' ? 0 : modules.indexOf(start)
  if (first < 0) throw new Error('Unknown start module: ' + start)
  const runners = suite === 'all' ? ['vitest', 'playwright'] : ['vitest']
  return modules.slice(first).filter(module => frontends.includes(module))
    .flatMap(module => runners.map(runner => module + '/target/ui-reports/' + runner + '.xml'))
}

/** Check presence and emitter totals; Jenkins subsequently performs full JUnit XML parsing. */
export function verifyReports(root, paths) {
  return paths.map(path => {
    const file = resolve(root, path)
    if (!existsSync(file)) throw new Error('Missing expected UI report: ' + path)
    const xml = readFileSync(file, 'utf8')
    const header = xml.match(/<testsuites\b[^>]*>/)?.[0]
    if (!header || !xml.trimEnd().endsWith('</testsuites>')) throw new Error('Empty or invalid UI report: ' + path)
    const counts = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(name => {
      const value = header.match(new RegExp('\\b' + name + '="(\\d+)"'))?.[1]
      if (value === undefined && name !== 'skipped') throw new Error('Invalid UI report totals: ' + path)
      return [name, Number(value ?? 0)]
    }))
    if (counts.tests < 1) throw new Error('Empty UI test suite: ' + path)
    if (counts.failures + counts.errors > 0) throw new Error('UI report contains failed tests: ' + path)
    return { path, ...counts }
  })
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const [suite, start = 'beginning'] = process.argv.slice(2)
  const root = process.cwd()
  const pom = readFileSync(resolve(root, 'pom.xml'), 'utf8')
  const modules = [...pom.matchAll(/<module>([^<]+)<\/module>/g)].map(match => match[1])
  for (const result of verifyReports(root, expectedReports(suite, start, modules))) {
    console.log(JSON.stringify(result))
  }
}
