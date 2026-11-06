/** 拆分后端的 methodKey（com.foo.Bar#baz(int)）：包名带结尾的点，name 是 Bar.baz，params 带括号 */
export function methodParts(methodKey: string): { pkg: string; name: string; params: string } {
  const hash = methodKey.indexOf('#')
  const className = hash < 0 ? methodKey : methodKey.slice(0, hash)
  const rest = hash < 0 ? '' : methodKey.slice(hash + 1)
  const paren = rest.indexOf('(')
  const method = paren < 0 ? rest : rest.slice(0, paren)
  const params = paren < 0 ? '' : rest.slice(paren)
  const dot = className.lastIndexOf('.')
  const pkg = dot < 0 ? '' : className.slice(0, dot + 1)
  const simple = dot < 0 ? className : className.slice(dot + 1)
  return { pkg, name: method ? `${simple}.${method}` : simple, params }
}
