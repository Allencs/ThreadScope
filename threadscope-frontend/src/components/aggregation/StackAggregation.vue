<script setup lang="ts">
/**
 * StackAggregation — 相似堆栈聚合视图。
 * 将堆栈完全相同的线程合并展示，降低视觉噪音。
 */
import { ref, onMounted } from 'vue'
import { useAnalysisStore } from '@/stores/analysisStore'
import { STATE_COLORS, type ThreadState } from '@/types'

const store = useAnalysisStore()
const expandedGroups = ref<Set<string>>(new Set())

onMounted(() => {
  store.loadStackAggregations()
})

function toggleGroup(fingerprint: string) {
  if (expandedGroups.value.has(fingerprint)) {
    expandedGroups.value.delete(fingerprint)
  } else {
    expandedGroups.value.add(fingerprint)
  }
}
</script>

<template>
  <div class="stack-aggregation">
    <h2 class="page-title">Stack Aggregation</h2>
    <p class="page-desc">Threads with identical stack traces are grouped together to reduce visual noise.</p>

    <div class="agg-list stagger-children">
      <div v-for="group in store.stackAggregations" :key="group.fingerprint" class="agg-card">
        <div class="agg-header" @click="toggleGroup(group.fingerprint)">
          <span class="expand-icon">{{ expandedGroups.has(group.fingerprint) ? '▼' : '▶' }}</span>
          <span class="agg-count mono" :style="{ color: group.threadCount >= 10 ? 'var(--ts-danger)' : 'var(--ts-accent)' }">
            ×{{ group.threadCount }}
          </span>
          <span class="agg-top-method mono">
            {{ group.representativeStack?.[0]?.className?.split('.')?.pop() }}.{{ group.representativeStack?.[0]?.methodName }}
          </span>
          <div class="agg-states">
            <span v-for="(count, state) in group.stateDistribution" :key="state"
              class="mini-badge"
              :style="{ background: STATE_COLORS[state as ThreadState] + '20', color: STATE_COLORS[state as ThreadState] }"
            >
              {{ state }}: {{ count }}
            </span>
          </div>
        </div>

        <div v-if="expandedGroups.has(group.fingerprint)" class="agg-detail thread-expand-enter">
          <!-- Representative Stack -->
          <div class="stack-trace">
            <div v-for="(frame, idx) in group.representativeStack" :key="idx" class="stack-frame mono"
              :class="{ 'stack-frame--jdk': frame.className.startsWith('java.') || frame.className.startsWith('sun.') }">
              at <span class="frame-class">{{ frame.className }}</span>.<span class="frame-method">{{ frame.methodName }}</span>(<span class="frame-source">{{ frame.source }}</span>)
            </div>
          </div>

          <!-- Thread Names -->
          <div class="thread-names">
            <span class="names-label">Threads in group:</span>
            <div class="names-list">
              <span v-for="name in group.threadNames.slice(0, 20)" :key="name" class="name-tag mono">{{ name }}</span>
              <span v-if="group.threadNames.length > 20" class="names-more">+{{ group.threadNames.length - 20 }} more</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="store.stackAggregations.length === 0" class="empty-state">
      No aggregatable stack patterns found.
    </div>
  </div>
</template>

<style scoped>
.stack-aggregation { display: flex; flex-direction: column; gap: var(--ts-space-lg); }
.page-title { font-size: var(--ts-font-size-xl); font-weight: 600; }
.page-desc { font-size: var(--ts-font-size-sm); color: var(--ts-text-muted); }

.agg-list { display: flex; flex-direction: column; gap: var(--ts-space-sm); }

.agg-card { background: var(--ts-bg-surface); border: var(--ts-border); border-radius: var(--ts-radius-md); }

.agg-header {
  display: flex; align-items: center; gap: var(--ts-space-sm);
  padding: var(--ts-space-md); cursor: pointer;
  transition: background var(--ts-transition);
}
.agg-header:hover { background: var(--ts-bg-hover); }

.expand-icon { font-size: 10px; color: var(--ts-text-muted); width: 16px; }
.agg-count { font-size: var(--ts-font-size-lg); font-weight: 700; min-width: 48px; }
.agg-top-method { font-size: var(--ts-font-size-sm); color: var(--ts-text-primary); flex: 1; }
.agg-states { display: flex; gap: var(--ts-space-xs); margin-left: auto; }
.mini-badge { font-size: 10px; padding: 1px 6px; border-radius: var(--ts-radius-full); border: 1px solid var(--ts-border-color); }

.agg-detail { padding: 0 var(--ts-space-md) var(--ts-space-md); border-top: var(--ts-border); }

.stack-trace { padding: var(--ts-space-sm) 0; }
.stack-frame { font-size: 12px; padding: 2px 0; color: var(--ts-text-primary); }
.stack-frame--jdk { color: var(--ts-text-muted); }
.frame-class { color: var(--ts-text-secondary); }
.frame-method { color: var(--ts-accent); font-weight: 500; }
.frame-source { color: var(--ts-success); }

.thread-names { margin-top: var(--ts-space-sm); padding-top: var(--ts-space-sm); border-top: var(--ts-border); }
.names-label { font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); display: block; margin-bottom: var(--ts-space-xs); }
.names-list { display: flex; flex-wrap: wrap; gap: var(--ts-space-xs); }
.name-tag { font-size: 10px; padding: 1px 6px; background: var(--ts-bg-elevated); border: 1px solid var(--ts-border-color); border-radius: var(--ts-radius-sm); color: var(--ts-text-secondary); }
.names-more { font-size: 10px; color: var(--ts-accent); padding: 1px 6px; }

.empty-state { padding: var(--ts-space-2xl); text-align: center; color: var(--ts-text-muted); }
</style>
