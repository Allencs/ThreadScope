import type { CodeLayer, CodeLayerConfig } from '@/types'

/**
 * 栈帧三层分类 — 与后端 CodeLayerClassifier 同一套规则 (前缀列表由 /config/code-layers 下发)。
 * 前端可即时响应用户修改业务包，无需重新上传分析。
 */

const FALLBACK_JDK = ['java.', 'javax.', 'jdk.', 'sun.', 'com.sun.']

const PROXY_MARKERS = [
  '$$EnhancerBySpringCGLIB$$', '$$SpringCGLIB$$', '$$EnhancerByCGLIB$$', '$$FastClassBySpringCGLIB$$',
  '$$FastClassByCGLIB$$', '$HibernateProxy$', '$$_javassist', '$ByteBuddy$', '$MockitoMock$',
]

export type CodeLayerClassifier = (className: string) => CodeLayer

export function normalizePackages(packages: string[]): string[] {
  return [...new Set(
    packages
      .map(p => p.trim())
      .filter(p => p.length > 0)
      .map(p => (p.endsWith('.') || p.endsWith('$') ? p : p + '.')),
  )]
}

export function createClassifier(config: CodeLayerConfig | null, businessPackages: string[]): CodeLayerClassifier {
  const jdk = config?.jdkPrefixes ?? FALLBACK_JDK
  const framework = config?.frameworkPrefixes ?? []
  const business = normalizePackages(businessPackages)
  const cache = new Map<string, CodeLayer>()

  return (rawClassName: string): CodeLayer => {
    const cached = cache.get(rawClassName)
    if (cached) return cached
    const slash = rawClassName.indexOf('/')
    const cls = slash >= 0 ? rawClassName.slice(slash + 1) : rawClassName
    const proxy = PROXY_MARKERS.some(m => cls.includes(m))

    let layer: CodeLayer
    if (!proxy && business.some(p => cls.startsWith(p))) layer = 'BUSINESS'
    else if (jdk.some(p => cls.startsWith(p))) layer = 'JDK'
    else if (proxy || cls.startsWith('$Proxy') || cls.includes('.$Proxy')) layer = 'FRAMEWORK'
    else if (framework.some(p => cls.startsWith(p))) layer = 'FRAMEWORK'
    else layer = business.length === 0 ? 'BUSINESS' : 'FRAMEWORK'

    cache.set(rawClassName, layer)
    return layer
  }
}

/** "org.springframework.web.servlet.DispatcherServlet" → "springframework" — 折叠行中用于概括框架来源 */
export function frameworkLabel(className: string): string {
  const parts = className.split('.')
  if (parts.length >= 3 && ['org', 'com', 'io', 'net', 'ch', 'co', 'cn'].includes(parts[0])) return parts[1]
  return parts[0] ?? className
}
