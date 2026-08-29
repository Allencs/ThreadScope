<script setup lang="ts">
/**
 * ComparisonView — 多 Dump 差分对比视图。
 *
 * 展示：每份 dump 的快照摘要、栈不动的可疑线程、CPU 时间增量榜、线程数趋势。
 * 仅当分析包含多份 dump (dumpCount > 1) 时通过导航可见。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import * as api from '@/api/threadscope'
import { STATE_COLORS, type DumpComparison, type StuckThread } from '@/types'

const store = useAnalysisStore()
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorMsg = ref('')
const comparison = ref<DumpComparison | null>(null)
const expandedStuck = ref<Set<string>>(new Set())

onMounted(load)

async function load() {
  if (!store.analysisId) return
  loading.value = true
  errorMsg.value = ''
  try {
    comparison.value = await api.fetchComparison(store.analysisId)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : 'Failed to load comparison'
  } finally {
    loading.value = false
  }
}

function toggleStuck(name: string) {
  const s = new Set(expandedStuck.value)
  if (s.has(name)) s.delete(name)
  else s.add(name)
  expandedStuck.value = s
}

function openThread(threadName: string) {
  store.openThreadDetailTab(threadName)
  router.push({ name: 'threads', params: { analysisId: route.params.analysisId } })
}

function fmtCpu(ms: number): string {
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms.toFixed(0) + 'ms'
}

function stuckHint(t: StuckThread): string {
  if (t.state === 'BLOCKED') return '持续被锁阻塞'
  if (t.state === 'RUNNABLE') return '疑似死循环 / 长计算'
  return '长时间等待未被唤醒'
}
</script>

<template>
  <div class="compare-view">
    <h2 class="page-title">Dump Comparison</h2>
    <p class="page-desc">
      Differential analysis across {{ comparison?.dumpCount ?? '...' }} dumps —
      threads with frozen stacks, real CPU consumers, and thread count trends.
    </p>

    <div v-if="errorMsg" class="error-banner">
      <span>{{ errorMsg }}</span>
      <button class="mini-btn" @click="load()">Retry</button>
    </div>

    <div v-if="loading" class="empty-state">Loading comparison...</div>

    <template v-else-if="comparison">
      <!-- ── 快照摘要 ── -->
      <section class="panel">
        <h3 class="section-title">Snapshots</h3>
        <div class="snapshot-row">
          <div v-for="snap in comparison.snapshots" :key="snap.index" class="snapshot-card">
            <div class="snapshot-idx mono">#{{ snap.index + 1 }}</div>
            <div class="snapshot-time mono">{{ snap.timestamp || 'no timestamp' }}</div>
            <div class="snapshot-total">{{ snap.totalThreads }} threads</div>
            <div class="snapshot-states">
              <span
                v-for="(count, state) in snap.stateDistribution"
                :key="state"
                class="mini-badge mono"
                :style="{ background: STATE_COLORS[state] + '20', color: STATE_COLORS[state] }"
              >{{ state }}: {{ count }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ── 栈不动线程 ── -->
      <section class="panel">
        <h3 class="section-title">
          Stuck Threads
          <span class="count-tag" :class="{ 'count-tag--danger': comparison.stuckThreads.length > 0 }">
            {{ comparison.stuckThreads.length }}
          </span>
        </h3>
        <p class="section-desc">
          Identical stack across all {{ comparison.dumpCount }} dumps (idle pool waiting excluded) —
          these are genuinely hung, looping, or in a long transaction.
        </p>
        <div v-if="comparison.stuckThreads.length === 0" class="empty-state">
          No stuck threads detected. 🎉
        </div>
        <div v-else class="stuck-list">
          <div v-for="t in comparison.stuckThreads" :key="t.threadName" class="stuck-card">
            <div class="stuck-header" @click="toggleStuck(t.threadName)">
              <span class="expand-icon">{{ expandedStuck.has(t.threadName) ? '▼' : '▶' }}</span>
              <span
                class="state-badge mono"
                :style="{ background: STATE_COLORS[t.state] + '20', color: STATE_COLORS[t.state] }"
              >{{ t.state }}</span>
              <span class="stuck-name mono" @click.stop="openThread(t.threadName)">{{ t.threadName }}</span>
              <span class="stuck-hint">{{ stuckHint(t) }}</span>
              <span class="stuck-top mono">{{ t.topMethod }}</span>
            </div>
            <div v-if="expandedStuck.has(t.threadName)" class="stuck-frames">
              <div v-for="(frame, i) in t.topFrames" :key="i" class="mono frame-line">at {{ frame }}</div>
            </div>
          </div>
        </div>
      </section>

      <!-- ── CPU 增量 ── -->
      <section class="panel">
        <h3 class="section-title">Top CPU Consumers <span class="section-sub">(first → last dump delta)</span></h3>
        <p class="section-desc">
          The cpu= field is cumulative — only its delta between dumps reflects real CPU usage.
        </p>
        <div v-if="comparison.topCpuThreads.length === 0" class="empty-state">
          No CPU deltas available (dumps may lack the cpu= field).
        </div>
        <table v-else class="data-table">
          <thead>
            <tr>
              <th>Thread</th><th>CPU Δ</th><th>First</th><th>Last</th><th>State</th><th>Top Method</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in comparison.topCpuThreads" :key="t.threadName">
              <td class="mono clickable" @click="openThread(t.threadName)">{{ t.threadName }}</td>
              <td class="mono cpu-delta">{{ fmtCpu(t.cpuMsDelta) }}</td>
              <td class="mono text-muted">{{ fmtCpu(t.firstCpuMs) }}</td>
              <td class="mono text-muted">{{ fmtCpu(t.lastCpuMs) }}</td>
              <td>
                <span
                  class="state-badge mono"
                  :style="{ background: STATE_COLORS[t.lastState] + '20', color: STATE_COLORS[t.lastState] }"
                >{{ t.lastState }}</span>
              </td>
              <td class="mono text-muted">{{ t.topMethod }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ── 线程数趋势 ── -->
      <section class="panel">
        <h3 class="section-title">Thread Count Trends <span class="section-sub">(grouped by name prefix)</span></h3>
        <div v-if="comparison.threadTrends.length === 0" class="empty-state">
          Thread counts are stable across dumps.
        </div>
        <table v-else class="data-table">
          <thead>
            <tr><th>Group</th><th>Counts per dump</th><th>Change</th></tr>
          </thead>
          <tbody>
            <tr v-for="trend in comparison.threadTrends" :key="trend.groupName">
              <td class="mono">{{ trend.groupName }}</td>
              <td class="mono">{{ trend.counts.join(' → ') }}</td>
              <td class="mono" :class="{
                'trend-up': trend.counts[trend.counts.length - 1] > trend.counts[0],
                'trend-down': trend.counts[trend.counts.length - 1] < trend.counts[0],
              }">
                {{ trend.counts[trend.counts.length - 1] - trend.counts[0] > 0 ? '+' : '' }}{{ trend.counts[trend.counts.length - 1] - trend.counts[0] }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <div v-else-if="!loading" class="empty-state">
      This analysis contains a single dump — upload multiple dumps taken seconds apart to enable comparison.
    </div>
  </div>
</template>

<style scoped>
.compare-view { display: flex; flex-direction: column; gap: var(--ts-space-lg); }
.page-title { font-size: var(--ts-font-size-xl); font-weight: 600; }
.page-desc { font-size: var(--ts-font-size-sm); color: var(--ts-text-muted); }

.panel {
  background: var(--ts-bg-surface); border: var(--ts-border);
  border-radius: var(--ts-radius-lg); padding: var(--ts-space-lg);
}

.section-title {
  font-size: var(--ts-font-size-md); font-weight: 600;
  display: flex; align-items: center; gap: var(--ts-space-sm);
}
.section-sub { font-weight: 400; font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); }
.section-desc { font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); margin: 4px 0 var(--ts-space-md); }

.count-tag {
  padding: 1px 8px; border-radius: 10px; font-size: var(--ts-font-size-xs);
  background: var(--ts-bg-primary); color: var(--ts-text-muted);
}
.count-tag--danger { background: #fef2f2; color: var(--ts-danger); }

.error-banner {
  display: flex; align-items: center; gap: var(--ts-space-md);
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: #fef2f2; border: 1px solid #fecaca; border-radius: var(--ts-radius-md);
  color: var(--ts-danger); font-size: var(--ts-font-size-sm);
}

.mini-btn {
  padding: 3px 10px; font-size: var(--ts-font-size-xs);
  border: var(--ts-border); border-radius: var(--ts-radius-md);
  background: var(--ts-bg-surface); cursor: pointer;
}

.empty-state { padding: var(--ts-space-lg); text-align: center; color: var(--ts-text-muted); font-size: var(--ts-font-size-sm); }

/* Snapshots */
.snapshot-row { display: flex; gap: var(--ts-space-md); flex-wrap: wrap; margin-top: var(--ts-space-md); }
.snapshot-card {
  flex: 1; min-width: 180px;
  border: var(--ts-border); border-radius: var(--ts-radius-md);
  padding: var(--ts-space-md); background: var(--ts-bg-primary);
}
.snapshot-idx { font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); }
.snapshot-time { font-size: var(--ts-font-size-xs); color: var(--ts-text-secondary); margin: 2px 0; }
.snapshot-total { font-size: var(--ts-font-size-lg); font-weight: 600; margin: 4px 0; }
.snapshot-states { display: flex; flex-wrap: wrap; gap: 4px; }

.mini-badge { padding: 1px 6px; border-radius: 4px; font-size: 10px; }
.state-badge { padding: 2px 8px; border-radius: 4px; font-size: var(--ts-font-size-xs); white-space: nowrap; }

/* Stuck threads */
.stuck-list { display: flex; flex-direction: column; gap: var(--ts-space-xs); }
.stuck-card { border: var(--ts-border); border-radius: var(--ts-radius-md); }
.stuck-header {
  display: flex; align-items: center; gap: var(--ts-space-sm);
  padding: var(--ts-space-sm) var(--ts-space-md); cursor: pointer;
  font-size: var(--ts-font-size-sm);
}
.stuck-header:hover { background: var(--ts-bg-primary); }
.expand-icon { font-size: 10px; color: var(--ts-text-muted); }
.stuck-name { font-weight: 600; }
.stuck-name:hover { color: var(--ts-accent); text-decoration: underline; }
.stuck-hint { font-size: var(--ts-font-size-xs); color: var(--ts-danger); white-space: nowrap; }
.stuck-top { margin-left: auto; color: var(--ts-text-muted); font-size: var(--ts-font-size-xs); overflow: hidden; text-overflow: ellipsis; }
.stuck-frames { padding: var(--ts-space-sm) var(--ts-space-lg); border-top: var(--ts-border); background: var(--ts-bg-primary); }
.frame-line { font-size: var(--ts-font-size-xs); color: var(--ts-text-secondary); line-height: 1.7; }

/* Tables */
.data-table { width: 100%; border-collapse: collapse; font-size: var(--ts-font-size-sm); }
.data-table th {
  text-align: left; padding: 6px 10px; font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted); border-bottom: var(--ts-border); font-weight: 500;
}
.data-table td { padding: 6px 10px; border-bottom: 1px solid #f1f5f9; }
.clickable { cursor: pointer; }
.clickable:hover { color: var(--ts-accent); text-decoration: underline; }
.cpu-delta { font-weight: 600; color: var(--ts-danger); }
.text-muted { color: var(--ts-text-muted); }
.trend-up { color: var(--ts-danger); font-weight: 600; }
.trend-down { color: #16a34a; }
</style>
