const KB = 1024
const MB = KB * 1024
const GB = MB * 1024
const TB = GB * 1024

function trim(v: number, digits: number): string {
  const text = v.toFixed(digits)
  return digits > 0 ? text.replace(/\.?0+$/, '') : text
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  const abs = Math.abs(bytes)
  if (abs >= TB) return `${trim(bytes / TB, 2)} TB`
  if (abs >= GB) return `${trim(bytes / GB, abs >= 100 * GB ? 0 : 2)} GB`
  if (abs >= MB) return `${trim(bytes / MB, abs >= 100 * MB ? 0 : 1)} MB`
  if (abs >= KB) return `${trim(bytes / KB, 0)} KB`
  return `${Math.round(bytes)} B`
}

export function formatRate(bytesPerSecond: number | null | undefined): string {
  return bytesPerSecond == null ? '—' : `${formatBytes(bytesPerSecond)}/s`
}

export function formatNanos(nanos: number | null | undefined): string {
  if (nanos == null) return '—'
  const ms = nanos / 1e6
  if (ms >= 1000) return `${trim(ms / 1000, 2)} s`
  if (ms >= 10) return `${Math.round(ms)} ms`
  if (ms >= 0.1) return `${trim(ms, 2)} ms`
  return `${Math.round(nanos / 1000)} µs`
}
