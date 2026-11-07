<script setup lang="ts">
import { inject, ref, type Ref } from 'vue'
import type { JfrAllocationSort, JfrCaller } from '@/api/threadscope'

defineOptions({ name: 'CallerTree' })
/** path 是从热点根方法到当前这一层父节点的方法链。 */
const props = defineProps<{ callers: JfrCaller[]; depth: number; path: string[]; basis: JfrAllocationSort }>()

const loadCallers = inject<(path: string[], basis: JfrAllocationSort) => Promise<JfrCaller[]>>('jfrLoadCallers')
const totalSamples = inject<Ref<number>>('jfrTotalSamples')
/** 分配树的权重是字节数，由内存面板提供格式化 */
const formatWeight = inject<(value: number) => string>('jfrFormatWeight', value => `${value.toLocaleString()} 样本`)
const collapsed = ref<Record<string, boolean>>({})
const loaded = ref<Record<string, JfrCaller[]>>({})
const loading = ref<Record<string, boolean>>({})

function children(caller: JfrCaller): JfrCaller[] {
  return caller.callers.length ? caller.callers : loaded.value[caller.methodKey] ?? []
}

function expandable(caller: JfrCaller): boolean {
  return !caller.kind && (caller.callers.length > 0 || caller.truncated)
}

function share(caller: JfrCaller): string {
  const total = totalSamples?.value ?? 0
  const pct = total > 0 ? (caller.samples * 100) / total : 0
  return `${formatWeight(caller.samples)} · 占全部 ${pct < 0.01 && pct > 0 ? '<0.01' : pct.toFixed(2)}%`
}

async function toggle(caller: JfrCaller) {
  const key = caller.methodKey
  if (caller.truncated && !caller.callers.length && !loaded.value[key]) {
    if (!loadCallers || loading.value[key]) return
    loading.value[key] = true
    try {
      loaded.value[key] = await loadCallers([...props.path, key], props.basis)
      collapsed.value[key] = false
    } finally {
      loading.value[key] = false
    }
    return
  }
  collapsed.value[key] = !collapsed.value[key]
}

function isOpen(caller: JfrCaller): boolean {
  return children(caller).length > 0 && !collapsed.value[caller.methodKey]
}

function parts(methodKey: string): { pkg: string; name: string; params: string } {
  const hash = methodKey.indexOf('#')
  const className = hash < 0 ? methodKey : methodKey.slice(0, hash)
  const rest = hash < 0 ? '' : methodKey.slice(hash + 1)
  const paren = rest.indexOf('(')
  const method = paren < 0 ? rest : rest.slice(0, paren)
  const dot = className.lastIndexOf('.')
  const pkg = dot < 0 ? '' : className.slice(0, dot + 1)
  const simple = dot < 0 ? className : className.slice(dot + 1)
  return { pkg, name: method ? `${simple}.${method}` : simple, params: paren < 0 ? '' : rest.slice(paren) }
}

/** 调用链常有几十层：10 层以后缩进放缓，且最多占行宽的 45%，保证方法名始终有空间。 */
function indent(depth: number): string {
  const px = depth <= 10 ? depth * 14 : 140 + (depth - 10) * 6
  return `min(${px}px, 45%)`
}

function tone(percent: number): string {
  if (percent >= 50) return 'hot'
  if (percent >= 20) return 'warm'
  if (percent >= 5) return 'mid'
  return 'cool'
}
</script>

<template>
  <div class="tree" :class="{ root: depth <= 1 }">
    <div v-for="caller in callers" :key="caller.methodKey" class="node">
      <div
        class="row"
        :class="{ hit: caller.matched, clickable: expandable(caller), summary: caller.kind }"
        :style="{ paddingLeft: indent(depth) }"
        :title="caller.kind ? share(caller) : `${caller.methodKey}\n${caller.parentPercent.toFixed(2)}% · ${share(caller)}`"
        @click.stop="expandable(caller) && toggle(caller)"
      >
        <span class="chevron">
          <template v-if="loading[caller.methodKey]"><i class="mini-spin"></i></template>
          <template v-else-if="expandable(caller)">{{ isOpen(caller) ? '▾' : '▸' }}</template>
        </span>
        <span v-if="caller.kind" class="summary-label">{{ caller.label }}</span>
        <template v-else>
          <span class="pkg">{{ parts(caller.methodKey).pkg }}</span><span class="method">{{ parts(caller.methodKey).name }}</span><span class="params">{{ parts(caller.methodKey).params }}</span>
        </template>
        <span class="badge" :class="caller.kind ? 'none' : tone(caller.parentPercent)">{{ caller.parentPercent.toFixed(2) }}%</span>
        <span class="more">
          {{ share(caller) }}<template v-if="caller.truncated && !loaded[caller.methodKey]"> · 点击加载调用者</template>
        </span>
      </div>
      <CallerTree
        v-if="isOpen(caller)"
        :callers="children(caller)"
        :depth="depth + 1"
        :path="[...path, caller.methodKey]"
        :basis="basis"
      />
    </div>
  </div>
</template>

<style scoped>
.tree { display: block; }
/* 最外层横向滚动：方法名完整显示，宽度取最长的一行，所有行等宽，悬停底色铺满 */
.tree.root { overflow-x: auto; overscroll-behavior-x: contain; padding-bottom: 2px; }
.tree.root > .node { width: max-content; min-width: 100%; }
.node { display: block; }
.row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
  min-height: 26px;
  padding-top: 2px;
  padding-bottom: 2px;
  border-radius: 4px;
}
.row.clickable { cursor: pointer; }
.row:hover { background: var(--ts-bg-hover); }
.row.hit { background: var(--ts-accent-light); }
.chevron {
  width: 16px;
  flex: none;
  color: var(--ts-text-muted);
  font-size: 12px;
  line-height: 1;
}
.mini-spin {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 1.5px solid #dbe3f0;
  border-top-color: var(--ts-accent);
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.pkg, .method, .params, .summary-label { flex: none; }
.pkg { color: var(--ts-text-primary); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.method { color: var(--ts-accent); font-weight: 650; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.params { color: var(--ts-text-muted); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; }
.badge {
  flex: none;
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 18px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: #fff;
}
.badge.hot { background: #db2777; }
.badge.warm { background: #ea580c; }
.badge.mid { background: #d97706; }
.badge.cool { background: #ca8a04; }
.badge.none { background: #e2e8f0; color: var(--ts-text-secondary); }
.summary-label { color: var(--ts-text-muted); font-size: 12.5px; font-style: italic; }
/* 跟在徽标后面而不是贴右边：行可能比可视区宽，贴右边会滚出视野 */
.more {
  flex: none;
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  font-size: 11px;
  line-height: 18px;
  color: var(--ts-text-secondary);
  pointer-events: none;
  opacity: 0;
  transition: opacity var(--ts-transition);
}
.row:hover .more { opacity: 1; }
</style>
