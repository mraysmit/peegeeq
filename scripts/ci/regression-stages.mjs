import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { frontends } from './check-ui-reports.mjs'

/**
 * Maven arguments for one stage of the full regression, or null when the stage has no module
 * at or after the start module. Maven orders the reactor: `modules` is the declaration order of
 * the root pom.xml, which is not the build order, so a Java start module is passed to `-rf`.
 */
export function stageMavenArguments(stage, start, modules) {
  if (!['java', 'ui'].includes(stage)) throw new Error('Unknown stage: ' + stage)
  const first = start === 'beginning' ? 0 : modules.indexOf(start)
  if (first < 0) throw new Error('Unknown start module: ' + start)
  const ui = modules.filter(module => frontends.includes(module))
  // A UI start module leaves no Java stage only if no Java module follows a UI module.
  if (modules.slice(modules.length - ui.length).some(module => !frontends.includes(module))) {
    throw new Error('UI modules must be declared last in pom.xml: ' + ui.join(', '))
  }
  if (stage === 'ui') {
    const selected = modules.slice(first).filter(module => frontends.includes(module))
    return selected.length === 0 ? null : '-Pall-tests -pl ' + selected.map(module => ':' + module).join(',')
  }
  if (frontends.includes(start) || ui.length === modules.length) return null
  return ['-Pall-tests']
    .concat(ui.length === 0 ? [] : ['-pl ' + ui.map(module => '!:' + module).join(',')])
    .concat(start === 'beginning' ? [] : ['-rf :' + start])
    .join(' ')
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const [stage, start = 'beginning'] = process.argv.slice(2)
  const pom = readFileSync(resolve(process.cwd(), 'pom.xml'), 'utf8')
  const modules = [...pom.matchAll(/<module>([^<]+)<\/module>/g)].map(match => match[1])
  console.log(stageMavenArguments(stage, start, modules) ?? 'skip')
}
