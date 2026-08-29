<script setup lang="ts">
/**
 * FlameGraphView — 调用树火焰图。
 *
 * 所有线程的堆栈自底向上合并成 trie，宽度 = 经过该调用点的线程数。
 * 用绝对定位的 div 渲染（宽度按百分比），文字不会随缩放变形，也无图表库依赖。
 * 支持点击缩放聚焦子树。
 */
import { ref, computed, onMounted, shallowRef } from 'vue'
import { useAnalysisStore } from '@/stores/analysisStore'
import * as api from '@/api/threadscope'
import type { CallTreeNode, ThreadState } from '@/types'

const store = useAnalysisStore()

const loading = ref(false)
const errorMsg = ref('')
const tree = shallowRef<CallTreeNode | null>(null)
const focusNode = shallowRef<CallTreeNode | null>(null)
const stateFilter = ref<ThreadState | ''>('')
const hovered = ref<string>('')

const STATE_OPTIONS: (ThreadState | '')[] = ['', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING']

const ROW_H = 22
const MAX_ROWS = 60
/** 窄于该比例 (0.1%) 的节点不渲染 — 视觉不可见且拖慢 DOM */
const MIN_RATIO = 0.001

interface FlameCell {
  left: number   // 百分比 0~100
  width: number  // 百分比 0~100
  top: number    // px
  name: string
  value: number
  node: CallTreeNode
  color: string
}

onMounted(load)

async function load() {
  if (!store.analysisId) return
  loading.value = true
  errorMsg.value = ''
  try {
    tree.value = await api.fetchCallTree(store.analysisId, stateFilter.value || undefined)
    focusNode.value = null
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : 'Failed to load call tree'
  } finally {
    loading.value = false
  }
}

function setFilter(state: ThreadState | '') {
  stateFilter.value = state
  load()
}

/** 稳定的名字→色相映射，同名方法在缩放前后颜色一致 */
function colorFor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0
  }
  const hue = 18 + (Math.abs(hash) % 42) // 橙红暖色带
  const light = 58 + (Math.abs(hash >> 8) % 14)
  return `hsl(${hue}, 82%, ${light}%)`
}

const cells = computed<FlameCell[]>(() => {
  const root = focusNode.value ?? tree.value
  if (!root || root.value === 0) return []
  const out: FlameCell[] = []
  layout(root, 0, 1, 0, out)
  return out
})

function layout(node: CallTreeNode, x0: number, x1: number, depth: number, out: FlameCell[]) {
  const w = x1 - x0
  if (w < MIN_RATIO || depth > MAX_ROWS) return
  out.push({
    left: x0 * 100,
    width: w * 100,
    top: depth * ROW_H,
    name: node.name,
    value: node.value,
    node,
    color: depth === 0 ? '#94a3b8' : colorFor(node.name),
  })
  let childX = x0
  for (const child of node.children) {
    const childW = (child.value / node.value) * w
    layout(child, childX, childX + childW, depth + 1, out)
    childX += childW
  }
}

const graphHeight = computed(() => {
  const maxTop = cells.value.reduce((m, c) => Math.max(m, c.top), 0)
  return maxTop + ROW_H + 4
})

const totalValue = computed(() => (focusNode.value ?? tree.value)?.value ?? 0)

function zoomTo(cell: FlameCell) {
  if (cell.node === (focusNode.value ?? tree.value)) return
  focusNode.value = cell.node
}

function resetZoom() {
  focusNode.value = null
}

function percent(value: number): string {
  const base = tree.value?.value ?? 0
  return base > 0 ? ((value / base) * 100).toFixed(1) + '%' : '—'
}

function cellTitle(cell: FlameCell): string {
  return `${cell.name} — ${cell.value} threads (${percent(cell.value)})`
}
</script>

<template>
  <div class="flame-view">
    <div class="flame-header">
      <div>
        <h2 class="page-title">Flame Graph</h2>
        <p class="page-desc">
          All thread stacks merged bottom-up. Width = number of threads passing through a call site.
          Click a frame to zoom in.
        </p>
      </div>
      <div class="flame-controls">
        <button
          v-for="opt in STATE_OPTIONS"
          :key="opt || 'ALL'"
          class="filter-btn"
          :class="{ 'filter-btn--active': stateFilter === opt }"
          @click="setFilter(opt)"
        >{{ opt || 'ALL' }}</button>
        <button v-if="focusNode" class="filter-btn filter-btn--reset" @click="resetZoom">
          Reset Zoom
        </button>
      </div>
    </div>

    <div v-if="errorMsg" class="flame-error">
      <span>{{ errorMsg }}</span>
      <button class="filter-btn" @click="load()">Retry</button>
    </div>

    <div v-if="loading" class="flame-loading">Building call tree...</div>

    <div v-else-if="cells.length" class="flame-panel">
      <div class="flame-meta mono">
        {{ totalValue }} threads in view
        <span v-if="hovered" class="flame-hovered">· {{ hovered }}</span>
      </div>
      <div class="flame-graph" :style="{ height: graphHeight + 'px' }">
        <div
          v-for="(c, i) in cells"
          :key="i"
          class="flame-cell mono"
          :style="{
            left: c.left + '%',
            width: c.width + '%',
            top: c.top + 'px',
            height: ROW_H - 2 + 'px',
            background: c.color,
          }"
          :title="cellTitle(c)"
          @click="zoomTo(c)"
          @mouseenter="hovered = cellTitle(c)"
          @mouseleave="hovered = ''"
        >
          <span v-if="c.width > 4" class="flame-label">{{ c.name }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="!loading" class="empty-state">No stack data available.</div>
  </div>
</template>

<style scoped>
.flame-view { display: flex; flex-direction: column; gap: var(--ts-space-lg); }
.page-title { font-size: var(--ts-font-size-xl); font-weight: 600; }
.page-desc { font-size: var(--ts-font-size-sm); color: var(--ts-text-muted); margin-top: 2px; }

.flame-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: var(--ts-space-md); flex-wrap: wrap;
}

.flame-controls { display: flex; gap: var(--ts-space-xs); flex-wrap: wrap; }

.filter-btn {
  padding: 4px 10px; font-size: var(--ts-font-size-xs);
  border: var(--ts-border); border-radius: var(--ts-radius-md);
  background: var(--ts-bg-surface); color: var(--ts-text-secondary);
  cursor: pointer; transition: all var(--ts-transition);
}
.filter-btn:hover { border-color: var(--ts-accent); color: var(--ts-accent); }
.filter-btn--active { background: var(--ts-accent); color: #fff; border-color: var(--ts-accent); }
.filter-btn--reset { border-color: var(--ts-danger); color: var(--ts-danger); }

.flame-error {
  display: flex; align-items: center; gap: var(--ts-space-md);
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: #fef2f2; border: 1px solid #fecaca; border-radius: var(--ts-radius-md);
  color: var(--ts-danger); font-size: var(--ts-font-size-sm);
}

.flame-loading, .empty-state {
  padding: var(--ts-space-xl); text-align: center; color: var(--ts-text-muted);
}

.flame-panel {
  background: var(--ts-bg-surface); border: var(--ts-border);
  border-radius: var(--ts-radius-lg); padding: var(--ts-space-md);
}

.flame-meta {
  font-size: var(--ts-font-size-xs); color: var(--ts-text-muted);
  margin-bottom: var(--ts-space-sm); min-height: 16px;
}
.flame-hovered { color: var(--ts-text-primary); }

.flame-graph { position: relative; width: 100%; }

.flame-cell {
  position: absolute;
  border-radius: 2px;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  box-sizing: border-box;
  padding: 0 4px;
  font-size: 11px;
  line-height: 20px;
  color: #1f2937;
  transition: filter 0.1s;
}
.flame-cell:hover { filter: brightness(0.85); outline: 1px solid #1e293b; }

.flame-label { pointer-events: none; user-select: none; }
</style>
