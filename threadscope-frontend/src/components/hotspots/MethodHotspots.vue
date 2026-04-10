<script setup lang="ts">
/**
 * MethodHotspots — 方法热点分析视图。
 * 统计栈顶出现频率最高的方法，辅助定位CPU热点。
 */
import { onMounted, computed } from 'vue'
import { useAnalysisStore } from '@/stores/analysisStore'

const store = useAnalysisStore()

onMounted(() => {
  store.loadMethodHotspots()
})

const maxOccurrences = computed(() =>
  store.methodHotspots.length > 0 ? store.methodHotspots[0].occurrences : 1
)
</script>

<template>
  <div class="hotspots">
    <h2 class="page-title">Method Hotspots</h2>
    <p class="page-desc">Methods appearing most frequently at the top of stack traces (potential CPU hotspots).</p>

    <div class="hotspot-list stagger-children">
      <div v-for="(hs, idx) in store.methodHotspots" :key="idx" class="hotspot-row">
        <div class="hotspot-rank mono">{{ idx + 1 }}</div>
        <div class="hotspot-info">
          <div class="hotspot-method mono">
            <span class="method-class">{{ hs.className.split('.').pop() }}</span>.<span class="method-name">{{ hs.methodName }}</span>
          </div>
          <div class="hotspot-fullclass mono">{{ hs.className }}</div>
        </div>
        <div class="hotspot-bar-container">
          <div class="hotspot-bar" :style="{
            width: (hs.occurrences / maxOccurrences * 100) + '%',
            background: idx < 3 ? 'var(--ts-danger)' : idx < 7 ? 'var(--ts-warning)' : 'var(--ts-accent)'
          }"></div>
        </div>
        <div class="hotspot-count mono">{{ hs.occurrences }}</div>
        <div class="hotspot-pct mono">{{ hs.percentage.toFixed(1) }}%</div>
      </div>
    </div>

    <div v-if="store.methodHotspots.length === 0" class="empty-state">
      No hotspot data available.
    </div>
  </div>
</template>

<style scoped>
.hotspots { display: flex; flex-direction: column; gap: var(--ts-space-lg); }
.page-title { font-size: var(--ts-font-size-xl); font-weight: 600; }
.page-desc { font-size: var(--ts-font-size-sm); color: var(--ts-text-muted); }

.hotspot-list { display: flex; flex-direction: column; gap: 2px; }

.hotspot-row {
  display: grid;
  grid-template-columns: 32px 280px 1fr 56px 56px;
  align-items: center;
  gap: var(--ts-space-md);
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: var(--ts-bg-surface);
  border: 1px solid transparent;
  border-radius: var(--ts-radius-sm);
  transition: all var(--ts-transition);
}
.hotspot-row:hover { background: var(--ts-bg-hover); border-color: var(--ts-border-color); }

.hotspot-rank { font-size: var(--ts-font-size-sm); color: var(--ts-text-muted); text-align: center; }
.hotspot-method { font-size: var(--ts-font-size-sm); }
.method-class { color: var(--ts-text-secondary); }
.method-name { color: var(--ts-accent); font-weight: 600; }
.hotspot-fullclass { font-size: 10px; color: var(--ts-text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.hotspot-bar-container { height: 6px; background: var(--ts-bg-primary); border-radius: var(--ts-radius-full); overflow: hidden; }
.hotspot-bar { height: 100%; border-radius: var(--ts-radius-full); transition: width 0.6s cubic-bezier(0.16, 1, 0.3, 1); }

.hotspot-count { font-size: var(--ts-font-size-sm); font-weight: 700; text-align: right; }
.hotspot-pct { font-size: var(--ts-font-size-xs); color: var(--ts-text-muted); text-align: right; }

.empty-state { padding: var(--ts-space-2xl); text-align: center; color: var(--ts-text-muted); }
</style>
