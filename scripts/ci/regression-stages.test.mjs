import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expectedReports } from './check-ui-reports.mjs'
import { stageMavenArguments } from './regression-stages.mjs'

// Declaration order of the root pom.xml. Maven builds peegeeq-db before peegeeq-outbox.
const modules = ['peegeeq-api', 'peegeeq-outbox', 'peegeeq-db', 'peegeeq-management-ui', 'peegeeq-utilities-ui']
const withoutUi = '-Pall-tests -pl !:peegeeq-management-ui,!:peegeeq-utilities-ui'
const bothUi = '-Pall-tests -pl :peegeeq-management-ui,:peegeeq-utilities-ui'

test('a full run keeps the UI modules out of the Java stage', () => {
  assert.equal(stageMavenArguments('java', 'beginning', modules), withoutUi)
  assert.equal(stageMavenArguments('ui', 'beginning', modules), bothUi)
})
test('resuming at a Java module leaves the reactor order to Maven', () => {
  assert.equal(stageMavenArguments('java', 'peegeeq-db', modules), withoutUi + ' -rf :peegeeq-db')
  assert.equal(stageMavenArguments('ui', 'peegeeq-db', modules), bothUi)
})
test('resuming at the first UI module leaves no Java stage', () => {
  assert.equal(stageMavenArguments('java', 'peegeeq-management-ui', modules), null)
  assert.equal(stageMavenArguments('ui', 'peegeeq-management-ui', modules), bothUi)
})
test('resuming at the last UI module runs that module only', () => {
  assert.equal(stageMavenArguments('java', 'peegeeq-utilities-ui', modules), null)
  assert.equal(stageMavenArguments('ui', 'peegeeq-utilities-ui', modules), '-Pall-tests -pl :peegeeq-utilities-ui')
})
test('the UI stage runs exactly the modules whose reports are expected', () => {
  for (const start of ['beginning', ...modules]) {
    const expected = [...new Set(expectedReports('all', start, modules).map(path => path.split('/')[0]))]
    const selected = stageMavenArguments('ui', start, modules).replace('-Pall-tests -pl ', '').split(',')
    assert.deepEqual(selected, expected.map(module => ':' + module), start)
  }
})
test('a reactor with no UI module has a Java stage only', () => {
  assert.equal(stageMavenArguments('java', 'beginning', ['peegeeq-api']), '-Pall-tests')
  assert.equal(stageMavenArguments('ui', 'beginning', ['peegeeq-api']), null)
})
test('unknown selection fails explicitly', () => {
  assert.throws(() => stageMavenArguments('wrong', 'beginning', modules), /Unknown stage/)
  assert.throws(() => stageMavenArguments('java', 'wrong', modules), /Unknown start module/)
})
test('a UI module declared before a Java module fails explicitly', () => {
  assert.throws(() => stageMavenArguments('java', 'beginning', ['peegeeq-management-ui', 'peegeeq-api']),
    /must be declared last/)
})

const root = fileURLToPath(new URL('../../', import.meta.url))
const command = fileURLToPath(new URL('./regression-stages.mjs', import.meta.url))
function run(args, cwd) {
  const result = spawnSync(process.execPath, [command, ...args], { cwd, encoding: 'utf8', timeout: 30000 })
  if (result.error) throw result.error
  return result
}
test('the command prints the Maven arguments of a stage of the repository reactor', () => {
  const result = run(['java', 'peegeeq-native'], root)
  assert.equal(result.status, 0, result.stderr)
  assert.equal(result.stdout, withoutUi + ' -rf :peegeeq-native\n')
})
test('the command prints skip for a stage with no module', () => {
  const result = run(['java', 'peegeeq-utilities-ui'], root)
  assert.equal(result.status, 0, result.stderr)
  assert.equal(result.stdout, 'skip\n')
})
test('the command fails for an unknown start module and prints no selection', () => {
  const result = run(['java', 'wrong'], root)
  assert.notEqual(result.status, 0)
  assert.equal(result.stdout, '')
  assert.match(result.stderr, /Unknown start module/)
})
test('the command fails when the working directory holds no pom.xml', t => {
  const empty = mkdtempSync(join(tmpdir(), 'peegeeq-regression-stages-'))
  t.after(() => rmSync(empty, { recursive: true, force: true }))
  const result = run(['java', 'beginning'], empty)
  assert.notEqual(result.status, 0)
  assert.equal(result.stdout, '')
  assert.match(result.stderr, /pom\.xml/)
})
