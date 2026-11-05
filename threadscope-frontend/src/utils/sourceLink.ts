import type { StackFrame } from '@/types'

/**
 * 栈帧 → 源码链接。
 *
 * 模板占位符：
 *   {path}     包路径 + 文件名，如 com/acme/order/OrderService.java
 *   {file}     文件名，如 OrderService.java
 *   {line}     行号
 *   {class}    外部类全限定名 (去掉 $Inner)
 *   {package}  包名 (点分)
 *   {method}   方法名
 */
export interface SourceLinkRule {
  /** 包前缀；留空表示匹配所有业务代码 */
  prefix: string
  template: string
}

export const SOURCE_LINK_PRESETS: { label: string; template: string }[] = [
  { label: 'GitLab', template: 'https://gitlab.example.com/group/project/-/blob/master/src/main/java/{path}#L{line}' },
  { label: 'GitHub', template: 'https://github.com/org/repo/blob/main/src/main/java/{path}#L{line}' },
  { label: 'Gitea / Gogs', template: 'https://git.example.com/org/repo/src/branch/master/src/main/java/{path}#L{line}' },
  { label: 'IntelliJ IDEA (本机已打开项目)', template: 'http://localhost:63342/api/file/src/main/java/{path}:{line}' },
  { label: 'VS Code (本机)', template: 'vscode://file/ABSOLUTE/PROJECT/PATH/src/main/java/{path}:{line}' },
]

function fileOf(frame: StackFrame): string | null {
  if (!frame.source || frame.lineNumber <= 0) return null
  let src = frame.source
  const slash = src.lastIndexOf('/')
  if (slash >= 0) src = src.slice(slash + 1)
  const colon = src.lastIndexOf(':')
  const file = colon > 0 ? src.slice(0, colon) : src
  return /\.(java|kt|groovy|scala)$/.test(file) ? file : null
}

export function findRule(className: string, rules: SourceLinkRule[]): SourceLinkRule | null {
  let best: SourceLinkRule | null = null
  for (const r of rules) {
    if (!r.template.trim()) continue
    const p = r.prefix.trim()
    if (p && !className.startsWith(p)) continue
    if (!best || p.length > best.prefix.trim().length) best = r
  }
  return best
}

export function buildSourceUrl(frame: StackFrame, rules: SourceLinkRule[]): string | null {
  const file = fileOf(frame)
  if (!file) return null
  const rule = findRule(frame.className, rules)
  if (!rule) return null

  const outer = frame.className.split('$')[0]
  const lastDot = outer.lastIndexOf('.')
  const pkg = lastDot > 0 ? outer.slice(0, lastDot) : ''
  const path = (pkg ? pkg.replace(/\./g, '/') + '/' : '') + file

  return rule.template
    .replace(/\{path\}/g, path)
    .replace(/\{file\}/g, file)
    .replace(/\{line\}/g, String(frame.lineNumber))
    .replace(/\{class\}/g, outer)
    .replace(/\{package\}/g, pkg)
    .replace(/\{method\}/g, frame.methodName)
}

export function frameReference(frame: StackFrame): string {
  return `${frame.className}.${frame.methodName}(${frame.source})`
}
