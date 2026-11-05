<script setup lang="ts">
/**
 * LockGraphView — 锁等待关系图。
 *
 * 分层布局：边方向为 "等待者 → 锁 → 持有者"，按到根因的最长路径分列，
 * 根因线程 (持有锁、自己不等锁) 在最右侧，被拖住的线程向左展开。
 * 同一等待位置的大量线程已由后端折叠成分组节点。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAnalysisStore } from '@/stores/analysisStore'
import * as api from '@/api/threadscope'
import { STATE_COLORS, STATE_LABELS, type LockGraphEdge, type LockGraphNode, type LockGraphView } from '@/types'

const emit = defineEmits<{
  (e: 'open-thread', threadRef: string, label: string): void
  (e: 'open-lock', lockAddress: string): void
}>()

const store = useAnalysisStore()

const graph = ref<LockGraphView | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const includeConditionWaits = ref(false)
const onlyCritical = ref(false)
const selectedId = ref<string | null>(null)
const hoveredId = ref<string | null>(null)

async function load() {
  if (!store.analysisId) return
  loading.value = true
  errorMsg.value = ''
  try {
    graph.value = await api.fetchLockGraph(store.analysisId, includeConditionWaits.value)
    selectedId.value = null
    requestAnimationFrame(fitToView)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载锁关系图失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => store.analysisId, load)
watch(includeConditionWaits, load)
watch(onlyCritical, () => requestAnimationFrame(fitToView))

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  过滤：仅保留含根因 / 死锁的连通分量
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

const visible = computed(() => {
  const g = graph.value
  if (!g) return { nodes: [] as LockGraphNode[], edges: [] as LockGraphEdge[] }
  if (!onlyCritical.value) return { nodes: g.nodes, edges: g.edges }

  const adj = new Map<string, string[]>()
  for (const n of g.nodes) adj.set(n.id, [])
  for (const e of g.edges) {
    adj.get(e.from)?.push(e.to)
    adj.get(e.to)?.push(e.from)
  }
  const keep = new Set<string>()
  const seen = new Set<string>()
  for (const n of g.nodes) {
    if (seen.has(n.id)) continue
    const comp: string[] = []
    const stack = [n.id]
    seen.add(n.id)
    while (stack.length) {
      const cur = stack.pop()!
      comp.push(cur)
      for (const nb of adj.get(cur) ?? []) {
        if (!seen.has(nb)) { seen.add(nb); stack.push(nb) }
      }
    }
    const critical = comp.some(id => {
      const node = g.nodes.find(x => x.id === id)!
      return node.root || node.deadlock
    })
    if (critical) comp.forEach(id => keep.add(id))
  }
  return {
    nodes: g.nodes.filter(n => keep.has(n.id)),
    edges: g.edges.filter(e => keep.has(e.from) && keep.has(e.to)),
  }
})

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  分层布局
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

const NODE_W = 236
const NODE_H = 54
const LOCK_W = 196
const LOCK_H = 40
const COL_GAP = 300
const ROW_GAP = 16

interface Placed {
  node: LockGraphNode
  x: number
  y: number
  w: number
  h: number
  col: number
}

function sizeOf(n: LockGraphNode) {
  return n.type === 'LOCK' ? { w: LOCK_W, h: LOCK_H } : { w: NODE_W, h: NODE_H }
}

const layout = computed(() => {
  const { nodes, edges } = visible.value
  const out = new Map<string, Placed>()
  if (nodes.length === 0) return { placed: out, width: 0, height: 0 }

  const succ = new Map<string, string[]>()
  for (const n of nodes) succ.set(n.id, [])
  for (const e of edges) succ.get(e.from)?.push(e.to)

  // rank = 到汇点 (根因) 的最长路径；死锁环上的回边忽略
  const rank = new Map<string, number>()
  const visiting = new Set<string>()
  const dfs = (id: string): number => {
    if (rank.has(id)) return rank.get(id)!
    if (visiting.has(id)) return 0
    visiting.add(id)
    let r = 0
    for (const s of succ.get(id) ?? []) {
      if (visiting.has(s)) continue
      r = Math.max(r, dfs(s) + 1)
    }
    visiting.delete(id)
    rank.set(id, r)
    return r
  }
  nodes.forEach(n => dfs(n.id))
  const maxRank = Math.max(...rank.values())

  const byRank = new Map<number, LockGraphNode[]>()
  for (const n of nodes) {
    const r = rank.get(n.id)!
    if (!byRank.has(r)) byRank.set(r, [])
    byRank.get(r)!.push(n)
  }

  // 从最右列 (根因) 开始放置，左侧各列对齐到其后继的平均高度
  for (let r = 0; r <= maxRank; r++) {
    const col = byRank.get(r) ?? []
    const desired = new Map<string, number>()
    for (const n of col) {
      const ys = (succ.get(n.id) ?? [])
        .map(s => out.get(s))
        .filter((p): p is Placed => !!p)
        .map(p => p.y + p.h / 2)
      desired.set(n.id, ys.length ? ys.reduce((a, b) => a + b, 0) / ys.length : Number.POSITIVE_INFINITY)
    }
    col.sort((a, b) => {
      const da = desired.get(a.id)!
      const db = desired.get(b.id)!
      if (da !== db) return da - db
      return (b.blockedBehind + b.count) - (a.blockedBehind + a.count)
    })
    let cursor = 0
    for (const n of col) {
      const { w, h } = sizeOf(n)
      const want = desired.get(n.id)!
      const y = Math.max(cursor, Number.isFinite(want) ? want - h / 2 : cursor)
      const x = (maxRank - r) * COL_GAP + (NODE_W - w) / 2
      out.set(n.id, { node: n, x, y, w, h, col: maxRank - r })
      cursor = y + h + ROW_GAP
    }
  }

  let width = 0
  let height = 0
  for (const p of out.values()) {
    width = Math.max(width, p.x + p.w)
    height = Math.max(height, p.y + p.h)
  }
  return { placed: out, width, height }
})

function edgePath(e: LockGraphEdge): string {
  const a = layout.value.placed.get(e.from)
  const b = layout.value.placed.get(e.to)
  if (!a || !b) return ''
  const x1 = a.x + a.w
  const y1 = a.y + a.h / 2
  const x2 = b.x
  const y2 = b.y + b.h / 2
  if (x2 > x1) {
    const dx = (x2 - x1) / 2
    return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
  }
  // 回边 (死锁环)：从下方绕回
  const drop = Math.max(a.y + a.h, b.y + b.h) + 60
  return `M ${x1} ${y1} C ${x1 + 120} ${y1}, ${x1 + 120} ${drop}, ${(x1 + x2) / 2} ${drop} S ${x2 - 120} ${y2}, ${x2} ${y2}`
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  高亮：选中节点的上下游等待链
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

const focusId = computed(() => hoveredId.value ?? selectedId.value)

const chain = computed<Set<string> | null>(() => {
  const id = focusId.value
  if (!id) return null
  const { edges } = visible.value
  const fwd = new Map<string, string[]>()
  const back = new Map<string, string[]>()
  for (const e of edges) {
    if (!fwd.has(e.from)) fwd.set(e.from, [])
    if (!back.has(e.to)) back.set(e.to, [])
    fwd.get(e.from)!.push(e.to)
    back.get(e.to)!.push(e.from)
  }
  const result = new Set<string>([id])
  for (const adj of [fwd, back]) {
    const stack = [id]
    while (stack.length) {
      const cur = stack.pop()!
      for (const n of adj.get(cur) ?? []) {
        if (!result.has(n)) { result.add(n); stack.push(n) }
      }
    }
  }
  return result
})

function dimmed(id: string): boolean {
  return !!chain.value && !chain.value.has(id)
}

function edgeDimmed(e: LockGraphEdge): boolean {
  return !!chain.value && !(chain.value.has(e.from) && chain.value.has(e.to))
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  平移 / 缩放
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

const canvas = ref<HTMLDivElement | null>(null)
const view = ref({ x: 20, y: 20, k: 1 })
let drag: { sx: number; sy: number; ox: number; oy: number } | null = null
let dragMoved = false

function fitToView() {
  const el = canvas.value
  const { width, height } = layout.value
  if (!el || width === 0) return
  const pad = 40
  const k = Math.min(1.2, (el.clientWidth - pad * 2) / width, (el.clientHeight - pad * 2) / (height + 60))
  const scale = Math.max(0.15, k)
  view.value = {
    k: scale,
    x: (el.clientWidth - width * scale) / 2,
    y: Math.max(pad, (el.clientHeight - height * scale) / 2),
  }
}

const MIN_ZOOM = 0.1
const MAX_ZOOM = 3

/** 以画布中心为锚点缩放到指定比例，范围 10%~300%。 */
function setZoom(k: number) {
  const el = canvas.value
  if (!el) return
  const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, k))
  const px = el.clientWidth / 2
  const py = el.clientHeight / 2
  const ratio = next / view.value.k
  view.value = { k: next, x: px - (px - view.value.x) * ratio, y: py - (py - view.value.y) * ratio }
}

function zoomBy(factor: number) {
  setZoom(view.value.k * factor)
}

const zoomEditing = ref(false)
const zoomText = ref('100')

watch(() => view.value.k, (k) => {
  if (!zoomEditing.value) zoomText.value = String(Math.round(k * 100))
})

function commitZoom() {
  zoomEditing.value = false
  const n = Number.parseInt(zoomText.value.replace('%', '').trim(), 10)
  if (!Number.isFinite(n)) {
    zoomText.value = String(Math.round(view.value.k * 100))
    return
  }
  setZoom(n / 100)
  zoomText.value = String(Math.round(view.value.k * 100))
}

function onPointerDown(ev: PointerEvent) {
  drag = { sx: ev.clientX, sy: ev.clientY, ox: view.value.x, oy: view.value.y }
  dragMoved = false
}

function onPointerMove(ev: PointerEvent) {
  if (!drag) return
  const dx = ev.clientX - drag.sx
  const dy = ev.clientY - drag.sy
  if (Math.abs(dx) + Math.abs(dy) > 3) dragMoved = true
  view.value = { ...view.value, x: drag.ox + dx, y: drag.oy + dy }
}

function onPointerUp() {
  drag = null
}

function onBackgroundClick() {
  if (!dragMoved) selectedId.value = null
}

function selectNode(id: string) {
  if (dragMoved) return
  selectedId.value = selectedId.value === id ? null : id
}

/** 侧栏打开/关闭会改变画布宽度，平移半个侧栏宽度保持画面中心不动 */
const SIDE_PANEL_W = 300
watch(() => selectedId.value !== null, (open, wasOpen) => {
  if (open === wasOpen) return
  view.value = { ...view.value, x: view.value.x + (open ? -SIDE_PANEL_W / 2 : SIDE_PANEL_W / 2) }
})

window.addEventListener('pointerup', onPointerUp)
onBeforeUnmount(() => window.removeEventListener('pointerup', onPointerUp))

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  展示辅助
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

const selected = computed(() => selectedId.value ? layout.value.placed.get(selectedId.value)?.node ?? null : null)

const stats = computed(() => {
  const g = graph.value
  if (!g) return null
  return {
    locks: g.nodes.filter(n => n.type === 'LOCK').length,
    roots: g.nodes.filter(n => n.root).length,
    deadlock: g.nodes.some(n => n.deadlock),
    waiters: g.totalWaiters,
  }
})

function shortMethod(full: string | null): string {
  if (!full) return ''
  const parts = full.split('.')
  return parts.length >= 2 ? parts.slice(-2).join('.') : full
}

function stateColor(n: LockGraphNode): string {
  return n.state ? STATE_COLORS[n.state] : '#9ca3af'
}

const EDGE_COLORS: Record<string, string> = {
  WAITING_TO_LOCK: '#dc2626',
  PARKING: '#d97706',
  WAITING_ON: '#a16207',
  HELD_BY: '#16a34a',
}

const EDGE_LABELS: Record<string, string> = {
  WAITING_TO_LOCK: 'synchronized 等待 (BLOCKED)',
  PARKING: 'park 等待 (ReentrantLock / Condition)',
  WAITING_ON: 'Object.wait() 等待通知',
  HELD_BY: '锁 → 持有者',
}

function openSelectedThread(n: LockGraphNode) {
  if (n.threadKey) emit('open-thread', n.threadKey, n.label)
}

function onNodeDblClick(n: LockGraphNode) {
  if (n.type === 'THREAD') openSelectedThread(n)
  else if (n.type === 'LOCK' && n.lockAddress) emit('open-lock', n.lockAddress)
}
</script>

<template>
  <div class="lock-graph">
    <!-- Toolbar -->
    <div class="graph-toolbar">
      <div class="graph-stats" v-if="stats">
        <span><b class="mono">{{ stats.locks }}</b> 把锁</span>
        <span><b class="mono">{{ stats.waiters }}</b> 个等待线程</span>
        <span :class="{ 'text-danger': stats.roots > 0 }"><b class="mono">{{ stats.roots }}</b> 个根因线程</span>
        <span v-if="stats.deadlock" class="deadlock-pill">存在死锁</span>
      </div>
      <span class="toolbar-spacer"></span>
      <label class="toolbar-check">
        <input v-model="onlyCritical" type="checkbox" /> 只看根因链 / 死锁
      </label>
      <label class="toolbar-check" title="无持有者的 Condition.await / Object.wait 通常是空闲线程在等任务，默认隐藏">
        <input v-model="includeConditionWaits" type="checkbox" /> 显示条件等待
      </label>
      <div class="zoom-group">
        <button class="zoom-btn" title="缩小" @click="zoomBy(1 / 1.2)">−</button>
        <label class="zoom-input" title="输入缩放百分比，回车或点击空白处生效（10~300）">
          <input
            v-model="zoomText"
            class="zoom-value mono"
            inputmode="numeric"
            maxlength="3"
            @focus="zoomEditing = true; ($event.target as HTMLInputElement).select()"
            @blur="commitZoom"
            @keydown.enter="($event.target as HTMLInputElement).blur()"
          />
          <span class="zoom-unit">%</span>
        </label>
        <button class="zoom-btn" title="放大" @click="zoomBy(1.2)">+</button>
        <button class="zoom-btn zoom-btn--wide" title="适应画布" @click="fitToView">适应</button>
      </div>
    </div>

    <div v-if="graph?.truncated" class="graph-notice">
      关系图过大，已隐藏 {{ graph.hiddenComponents }} 组阻塞规模较小的关系，可在「锁列表」中查看全部。
    </div>

    <div class="graph-body">
      <!-- Canvas -->
      <div
        ref="canvas"
        class="graph-canvas"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @click="onBackgroundClick"
      >
        <div v-if="loading" class="graph-empty">加载中…</div>
        <div v-else-if="errorMsg" class="graph-empty text-danger">{{ errorMsg }}</div>
        <div v-else-if="visible.nodes.length === 0" class="graph-empty">
          <template v-if="onlyCritical">没有包含根因线程或死锁的锁等待关系。</template>
          <template v-else>未检测到锁竞争关系。</template>
        </div>

        <svg v-else class="graph-svg">
          <defs>
            <marker v-for="(color, type) in EDGE_COLORS" :id="'arrow-' + type" :key="type"
              viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" :fill="color" />
            </marker>
          </defs>
          <g :transform="`translate(${view.x},${view.y}) scale(${view.k})`">
            <!-- Edges -->
            <path
              v-for="(e, i) in visible.edges"
              :key="'e' + i"
              class="graph-edge"
              :class="{
                'graph-edge--dim': edgeDimmed(e),
                'graph-edge--deadlock': e.deadlock,
                'graph-edge--dashed': e.type === 'PARKING',
                'graph-edge--dotted': e.type === 'WAITING_ON',
              }"
              :d="edgePath(e)"
              :stroke="EDGE_COLORS[e.type]"
              :marker-end="'url(#arrow-' + e.type + ')'"
            />

            <!-- Nodes -->
            <g
              v-for="p in layout.placed.values()"
              :key="p.node.id"
              class="graph-node"
              :class="{
                'graph-node--dim': dimmed(p.node.id),
                'graph-node--selected': selectedId === p.node.id,
              }"
              :transform="`translate(${p.x},${p.y})`"
              @click.stop="selectNode(p.node.id)"
              @dblclick.stop="onNodeDblClick(p.node)"
              @mouseenter="hoveredId = p.node.id"
              @mouseleave="hoveredId = null"
            >
              <!-- Group: stacked cards -->
              <template v-if="p.node.type === 'GROUP'">
                <rect x="8" y="-8" :width="p.w" :height="p.h" rx="9" class="node-stack" />
                <rect x="4" y="-4" :width="p.w" :height="p.h" rx="9" class="node-stack" />
              </template>

              <rect
                :width="p.w"
                :height="p.h"
                :rx="p.node.type === 'LOCK' ? p.h / 2 : 9"
                class="node-box"
                :class="[
                  'node-box--' + p.node.type.toLowerCase(),
                  { 'node-box--root': p.node.root, 'node-box--deadlock': p.node.deadlock },
                ]"
              />
              <rect
                v-if="p.node.type !== 'LOCK'"
                x="0" y="0" width="5" :height="p.h" rx="2"
                :fill="stateColor(p.node)"
              />

              <foreignObject :width="p.w" :height="p.h">
                <div class="node-content" :class="'node-content--' + p.node.type.toLowerCase()">
                  <template v-if="p.node.type === 'LOCK'">
                    <span class="lock-icon">🔒</span>
                    <span class="node-lock-text">
                      <span class="node-title mono">{{ p.node.label }}</span>
                      <span class="node-sub mono">{{ p.node.lockAddress }}</span>
                    </span>
                  </template>
                  <template v-else>
                    <div class="node-line">
                      <span class="node-title mono" :title="p.node.label">{{ p.node.label }}</span>
                      <span v-if="p.node.deadlock" class="node-badge node-badge--deadlock">死锁</span>
                      <span v-else-if="p.node.root" class="node-badge node-badge--root">根因 · 拖住 {{ p.node.blockedBehind }}</span>
                    </div>
                    <div class="node-line">
                      <span class="node-state" :style="{ color: stateColor(p.node) }">
                        {{ p.node.state ? STATE_LABELS[p.node.state] : '' }}
                      </span>
                      <span class="node-sub mono" :title="p.node.topMethod ?? ''">{{ shortMethod(p.node.topMethod) }}</span>
                    </div>
                  </template>
                </div>
              </foreignObject>
            </g>
          </g>
        </svg>

        <!-- Legend -->
        <div class="graph-legend">
          <div v-for="(label, type) in EDGE_LABELS" :key="type" class="legend-row">
            <svg width="26" height="8"><line x1="0" y1="4" x2="26" y2="4" :stroke="EDGE_COLORS[type]" stroke-width="2"
              :stroke-dasharray="type === 'PARKING' ? '5 3' : type === 'WAITING_ON' ? '2 3' : ''" /></svg>
            <span>{{ label }}</span>
          </div>
          <div class="legend-row legend-hint">拖拽平移 · 工具栏缩放 · 单击高亮等待链 · 双击打开</div>
        </div>
      </div>

      <!-- Side panel -->
      <aside v-if="selected" class="graph-side">
        <div class="side-header">
          <span class="side-type">{{ selected.type === 'LOCK' ? '锁' : selected.type === 'GROUP' ? '线程分组' : '线程' }}</span>
          <button class="side-close" @click="selectedId = null">×</button>
        </div>

        <template v-if="selected.type === 'THREAD'">
          <div class="side-title mono">{{ selected.label }}</div>
          <div class="side-row">
            <span class="side-label">状态</span>
            <span :style="{ color: stateColor(selected) }">{{ selected.state }}</span>
          </div>
          <div v-if="selected.topMethod" class="side-row side-row--block">
            <span class="side-label">所在位置</span>
            <span class="mono side-method">{{ selected.topMethod }}</span>
          </div>
          <div v-if="selected.root" class="side-callout side-callout--danger">
            根因线程：它持有锁且自身没有在等锁，直接或间接拖住了 <b>{{ selected.blockedBehind }}</b> 个线程。优先排查它为何长时间持锁。
          </div>
          <div v-else-if="selected.blockedBehind > 0" class="side-callout">
            它持有的锁拖住了 {{ selected.blockedBehind }} 个线程，但自身也在等待其他锁 (等待链中间节点)。
          </div>
          <div v-if="selected.deadlock" class="side-callout side-callout--danger">该线程位于死锁环上。</div>
          <button class="side-btn" @click="openSelectedThread(selected)">查看线程详情 →</button>
        </template>

        <template v-else-if="selected.type === 'GROUP'">
          <div class="side-title">{{ selected.count }} 个线程在同一位置等待</div>
          <div v-if="selected.topMethod" class="side-row side-row--block">
            <span class="side-label">等待位置</span>
            <span class="mono side-method">{{ selected.topMethod }}</span>
          </div>
          <div class="side-members">
            <div
              v-for="m in selected.members"
              :key="m.key"
              class="side-member mono"
              @click="emit('open-thread', m.key, m.name)"
            >{{ m.name }} <span class="side-member__go">→</span></div>
            <div v-if="selected.count > selected.members.length" class="side-label">
              仅显示前 {{ selected.members.length }} 个
            </div>
          </div>
        </template>

        <template v-else>
          <div class="side-title mono">{{ selected.lockClassName }}</div>
          <div class="side-row"><span class="side-label">地址</span><span class="mono">{{ selected.lockAddress }}</span></div>
          <div class="side-row"><span class="side-label">等待者</span><span class="mono">{{ selected.count }}</span></div>
          <button class="side-btn" @click="selected.lockAddress && emit('open-lock', selected.lockAddress)">在锁列表中查看 →</button>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.lock-graph {
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-sm);
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
  box-shadow: var(--ts-shadow-sm);
  overflow: hidden;
}

.graph-toolbar {
  display: flex;
  align-items: center;
  gap: var(--ts-space-md);
  padding: var(--ts-space-sm) var(--ts-space-md);
  border-bottom: var(--ts-border);
  font-size: var(--ts-font-size-sm);
  flex-wrap: wrap;
}

.graph-stats {
  display: flex;
  gap: var(--ts-space-md);
  color: var(--ts-text-secondary);
}
.graph-stats b { color: var(--ts-text-primary); }
.text-danger, .text-danger b { color: var(--ts-danger) !important; }

.deadlock-pill {
  padding: 0 8px;
  border-radius: var(--ts-radius-full);
  background: var(--ts-danger-light);
  color: var(--ts-danger);
  font-weight: 600;
  font-size: var(--ts-font-size-xs);
  line-height: 20px;
}

.toolbar-spacer { flex: 1; }

.toolbar-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ts-text-secondary);
  cursor: pointer;
  user-select: none;
}

.zoom-group { display: inline-flex; align-items: center; gap: 4px; }
.zoom-btn {
  min-width: 26px;
  height: 26px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  color: var(--ts-text-secondary);
  cursor: pointer;
}
.zoom-btn:hover { border-color: var(--ts-accent); color: var(--ts-accent); }
.zoom-btn--wide { padding: 0 10px; }
.zoom-input {
  display: inline-flex;
  align-items: center;
  height: 26px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  padding-right: 4px;
}
.zoom-input:focus-within { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.zoom-value {
  width: 36px;
  height: 100%;
  border: none;
  background: transparent;
  text-align: right;
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-primary);
  padding: 0 1px 0 4px;
  outline: none;
}
.zoom-unit { font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); }

.graph-notice {
  margin: 0 var(--ts-space-md);
  padding: 6px 12px;
  border-radius: var(--ts-radius-sm);
  background: var(--ts-warning-light);
  color: #92400e;
  font-size: var(--ts-font-size-xs);
}

.graph-body {
  display: flex;
  min-height: 0;
}

.graph-canvas {
  position: relative;
  flex: 1;
  height: max(480px, calc(100vh - 300px));
  overflow: hidden;
  cursor: grab;
  background-color: #fbfcfd;
  background-image: radial-gradient(#e5e7eb 1px, transparent 1px);
  background-size: 18px 18px;
  touch-action: none;
}
.graph-canvas:active { cursor: grabbing; }

.graph-svg { width: 100%; height: 100%; display: block; }

.graph-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-sm);
}

/* ── Edges ── */
.graph-edge {
  fill: none;
  stroke-width: 1.6;
  opacity: 0.85;
  transition: opacity var(--ts-transition);
}
.graph-edge--dashed { stroke-dasharray: 6 4; }
.graph-edge--dotted { stroke-dasharray: 2 4; }
.graph-edge--deadlock { stroke-width: 3; stroke-dasharray: 8 5; animation: dash-flow 0.8s linear infinite; }
.graph-edge--dim { opacity: 0.08; }

@keyframes dash-flow { to { stroke-dashoffset: -13; } }

/* ── Nodes ── */
.graph-node { cursor: pointer; transition: opacity var(--ts-transition); }
.graph-node--dim { opacity: 0.18; }

.node-box {
  fill: #fff;
  stroke: #d1d5db;
  stroke-width: 1;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.06));
}
.node-box--lock { fill: #f8fafc; stroke: #94a3b8; }
.node-box--group { fill: #fff; }
.node-box--root { stroke: #dc2626; stroke-width: 2; fill: #fff7f7; }
.node-box--deadlock { stroke: #dc2626; stroke-width: 2.5; fill: #fef2f2; }
.graph-node--selected .node-box { stroke: var(--ts-accent); stroke-width: 2.5; }
.node-stack { fill: #f3f4f6; stroke: #d1d5db; }

.node-content {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  padding: 7px 10px 7px 13px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  font-family: var(--ts-font-ui);
  overflow: hidden;
  pointer-events: none;
}
.node-content--lock {
  flex-direction: row;
  align-items: center;
  gap: 8px;
  padding: 4px 14px;
}

.node-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.node-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ts-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.node-sub {
  font-size: 10.5px;
  color: var(--ts-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.node-state { font-size: 10px; font-weight: 700; flex-shrink: 0; }

.node-lock-text { display: flex; flex-direction: column; min-width: 0; }
.lock-icon { font-size: 13px; flex-shrink: 0; }

.node-badge {
  flex-shrink: 0;
  font-size: 9.5px;
  font-weight: 700;
  padding: 0 6px;
  border-radius: var(--ts-radius-full);
  line-height: 16px;
}
.node-badge--root { background: #dc2626; color: #fff; }
.node-badge--deadlock { background: #7f1d1d; color: #fff; }

/* ── Legend ── */
.graph-legend {
  position: absolute;
  left: 12px;
  bottom: 12px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.92);
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  font-size: 11px;
  color: var(--ts-text-secondary);
  display: flex;
  flex-direction: column;
  gap: 3px;
  pointer-events: none;
}
.legend-row { display: flex; align-items: center; gap: 8px; }
.legend-hint { color: var(--ts-text-muted); margin-top: 2px; }

/* ── Side panel ── */
.graph-side {
  width: 300px; /* 与 SIDE_PANEL_W 一致 */
  flex-shrink: 0;
  border-left: var(--ts-border);
  padding: var(--ts-space-md);
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-sm);
  overflow-y: auto;
  max-height: max(480px, calc(100vh - 300px));
  font-size: var(--ts-font-size-sm);
}
.side-header { display: flex; align-items: center; justify-content: space-between; }
.side-type {
  font-size: var(--ts-font-size-xs);
  font-weight: 600;
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.side-close {
  border: none;
  background: transparent;
  font-size: 18px;
  color: var(--ts-text-muted);
  cursor: pointer;
}
.side-title { font-weight: 600; color: var(--ts-text-primary); word-break: break-all; }
.side-row { display: flex; gap: 8px; align-items: baseline; }
.side-row--block { flex-direction: column; gap: 2px; }
.side-label { color: var(--ts-text-muted); font-size: var(--ts-font-size-xs); flex-shrink: 0; }
.side-method { font-size: 11.5px; word-break: break-all; color: var(--ts-text-secondary); }
.side-callout {
  padding: 8px 10px;
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-inset);
  color: var(--ts-text-secondary);
  line-height: 1.6;
}
.side-callout--danger { background: var(--ts-danger-light); color: #991b1b; }
.side-btn {
  align-self: flex-start;
  padding: 5px 12px;
  border: 1px solid var(--ts-accent);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-accent-light);
  color: var(--ts-accent);
  cursor: pointer;
  font-size: var(--ts-font-size-sm);
}
.side-members { display: flex; flex-direction: column; gap: 2px; }
.side-member {
  padding: 3px 6px;
  border-radius: var(--ts-radius-xs);
  font-size: 11.5px;
  cursor: pointer;
  color: var(--ts-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.side-member:hover { background: var(--ts-bg-hover); color: var(--ts-accent); }
.side-member__go { opacity: 0; }
.side-member:hover .side-member__go { opacity: 1; }
</style>
