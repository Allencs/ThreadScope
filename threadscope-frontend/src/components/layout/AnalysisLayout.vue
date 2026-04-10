<script setup lang="ts">
/**
 * AnalysisLayout — Main analysis frame with topbar, sidebar, content, and statusbar.
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'

const route = useRoute()
const router = useRouter()
const store = useAnalysisStore()
const sidebarCollapsed = ref(false)

const navItems = [
  { name: 'dashboard', label: 'Overview' },
  { name: 'threads', label: 'Threads' },
  { name: 'locks', label: 'Locks' },
  { name: 'pools', label: 'Thread Pools' },
  { name: 'aggregation', label: 'Aggregation' },
  { name: 'hotspots', label: 'Hotspots' },
]

onMounted(async () => {
  const analysisId = route.params.analysisId as string
  if (analysisId && !store.overview) {
    store.analysisId = analysisId
    await store.loadOverview()
  }
})

function navigateTo(name: string) {
  const analysisId = route.params.analysisId as string
  router.push({ name, params: { analysisId } })
}

function handleNewUpload() {
  store.reset()
  router.push('/')
}
</script>

<template>
  <div class="analysis-layout">
    <!-- ── Top Bar ── -->
    <header class="topbar">
      <div class="topbar-left">
        <div class="topbar-brand" @click="handleNewUpload">
          <svg class="brand-logo" width="22" height="22" viewBox="0 0 24 24" fill="none">
            <rect x="1" y="1" width="22" height="22" rx="6" stroke="var(--ts-accent)" stroke-width="1.5" fill="none" />
            <path d="M7 17V10" stroke="var(--ts-accent)" stroke-width="1.8" stroke-linecap="round" />
            <path d="M12 17V7" stroke="var(--ts-accent)" stroke-width="1.8" stroke-linecap="round" />
            <path d="M17 17V12" stroke="var(--ts-accent)" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span class="topbar-title">ThreadScope</span>
        </div>
      </div>

      <div class="topbar-right">
        <button class="topbar-btn" @click="handleNewUpload" title="Upload new dump">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="17 8 12 3 7 8" />
            <line x1="12" y1="3" x2="12" y2="15" />
          </svg>
          <span>New Upload</span>
        </button>
      </div>
    </header>

    <!-- ── Body ── -->
    <div class="layout-body">
      <!-- Sidebar -->
      <nav class="sidebar" :class="{ 'sidebar--collapsed': sidebarCollapsed }">
        <div class="nav-list">
          <div
            v-for="item in navItems"
            :key="item.name"
            class="nav-item"
            :class="{ 'nav-item--active': route.name === item.name }"
            @click="navigateTo(item.name)"
            :title="sidebarCollapsed ? item.label : undefined"
          >
            <svg class="nav-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <!-- Overview — bar chart -->
              <template v-if="item.name === 'dashboard'">
                <rect x="3" y="12" width="4" height="8" rx="1" />
                <rect x="10" y="6" width="4" height="14" rx="1" />
                <rect x="17" y="9" width="4" height="11" rx="1" />
              </template>
              <!-- Threads — parallel lines -->
              <template v-else-if="item.name === 'threads'">
                <path d="M4 6h16" />
                <path d="M4 12h16" />
                <path d="M4 18h16" />
                <circle cx="8" cy="6" r="1.5" fill="currentColor" stroke="none" />
                <circle cx="14" cy="12" r="1.5" fill="currentColor" stroke="none" />
                <circle cx="10" cy="18" r="1.5" fill="currentColor" stroke="none" />
              </template>
              <!-- Locks — padlock -->
              <template v-else-if="item.name === 'locks'">
                <rect x="5" y="11" width="14" height="10" rx="2" />
                <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                <circle cx="12" cy="16" r="1.5" fill="currentColor" stroke="none" />
              </template>
              <!-- Pools — grid/layers -->
              <template v-else-if="item.name === 'pools'">
                <rect x="3" y="3" width="7" height="7" rx="1.5" />
                <rect x="14" y="3" width="7" height="7" rx="1.5" />
                <rect x="3" y="14" width="7" height="7" rx="1.5" />
                <rect x="14" y="14" width="7" height="7" rx="1.5" />
              </template>
              <!-- Aggregation — trending up -->
              <template v-else-if="item.name === 'aggregation'">
                <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
                <polyline points="16 7 22 7 22 13" />
              </template>
              <!-- Hotspots — flame -->
              <template v-else-if="item.name === 'hotspots'">
                <path d="M12 2c0 4-4 6-4 10a4 4 0 0 0 8 0c0-4-4-6-4-10Z" />
                <path d="M12 18a2 2 0 0 1-2-2c0-1.5 2-3 2-3s2 1.5 2 3a2 2 0 0 1-2 2Z" fill="currentColor" stroke="none" />
              </template>
            </svg>
            <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
          </div>
        </div>

        <button class="sidebar-toggle" @click="sidebarCollapsed = !sidebarCollapsed" :title="sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <template v-if="sidebarCollapsed">
              <polyline points="9 18 15 12 9 6" />
            </template>
            <template v-else>
              <polyline points="15 18 9 12 15 6" />
            </template>
          </svg>
        </button>
      </nav>

      <!-- Main Content -->
      <main class="main-content">
        <RouterView />
      </main>
    </div>

    <!-- ── Status Bar ── -->
    <footer class="statusbar">
      <div class="statusbar-left">
        <svg class="status-icon" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
        <span class="status-item mono">{{ store.fileName || 'No file' }}</span>
        <span class="status-dot"></span>
        <span class="status-item">{{ store.overview?.totalThreads ?? 0 }} threads</span>
        <span class="status-dot"></span>
        <span class="status-item">{{ store.overview?.parseTimeMs ?? 0 }}ms</span>
      </div>
      <div class="statusbar-right">
        <span v-if="store.overview?.jvmVersion" class="status-item mono">{{ store.overview.jvmVersion }}</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Analysis Layout — Modern Light Theme
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */

.analysis-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--ts-bg-primary);
  font-family: var(--ts-font-ui);
  color: var(--ts-text-primary);
}

/* ── TopBar ─────────────────────────────────────── */

.topbar {
  height: var(--ts-topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--ts-space-lg);
  background: var(--ts-bg-secondary);
  border-bottom: 1px solid var(--ts-border-color);
  box-shadow: var(--ts-shadow-xs);
  flex-shrink: 0;
  z-index: 10;
}

.topbar-left {
  display: flex;
  align-items: center;
}

.topbar-right {
  display: flex;
  align-items: center;
}

.topbar-brand {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  cursor: pointer;
  padding: var(--ts-space-xs) var(--ts-space-sm);
  border-radius: var(--ts-radius-md);
  transition: background var(--ts-transition);
  user-select: none;
}

.topbar-brand:hover {
  background: var(--ts-bg-hover);
}

.brand-logo {
  flex-shrink: 0;
}

.topbar-title {
  font-family: var(--ts-font-ui);
  font-size: var(--ts-font-size-lg);
  font-weight: 650;
  letter-spacing: -0.3px;
  color: var(--ts-text-primary);
}

.topbar-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: var(--ts-bg-secondary);
  border: 1px solid var(--ts-border-color);
  border-radius: var(--ts-radius-sm);
  color: var(--ts-text-secondary);
  font-family: var(--ts-font-ui);
  font-size: var(--ts-font-size-sm);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--ts-transition);
  white-space: nowrap;
}

.topbar-btn:hover {
  background: var(--ts-bg-hover);
  border-color: var(--ts-accent);
  color: var(--ts-accent);
  box-shadow: var(--ts-shadow-xs);
}

.topbar-btn:active {
  background: var(--ts-bg-active);
}

/* ── Layout Body ────────────────────────────────── */

.layout-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* ── Sidebar ────────────────────────────────────── */

.sidebar {
  width: var(--ts-sidebar-width);
  background: var(--ts-bg-secondary);
  border-right: 1px solid var(--ts-border-color);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width var(--ts-transition);
  overflow: hidden;
}

.sidebar--collapsed {
  width: var(--ts-sidebar-collapsed);
}

.nav-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: var(--ts-space-sm);
  gap: var(--ts-space-2xs);
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: 8px 12px;
  border-radius: var(--ts-radius-sm);
  cursor: pointer;
  transition: all var(--ts-transition);
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-md);
  white-space: nowrap;
  border-left: 3px solid transparent;
  margin-left: -1px;
}

.nav-item:hover {
  background: var(--ts-bg-hover);
  color: var(--ts-text-primary);
}

.nav-item--active {
  background: var(--ts-accent-light);
  color: var(--ts-accent);
  border-left-color: var(--ts-accent);
  font-weight: 500;
}

.nav-item--active:hover {
  background: var(--ts-accent-light);
  color: var(--ts-accent);
}

.nav-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
}

.nav-label {
  font-size: var(--ts-font-size-md);
  line-height: 1;
}

/* Collapsed state: center the icon */
.sidebar--collapsed .nav-item {
  justify-content: center;
  padding: 8px;
  border-left-color: transparent;
  margin-left: 0;
}

.sidebar--collapsed .nav-item--active {
  background: var(--ts-accent-light);
  color: var(--ts-accent);
}

.sidebar-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--ts-space-sm);
  margin: var(--ts-space-xs) var(--ts-space-sm) var(--ts-space-sm);
  border: none;
  background: transparent;
  border-radius: var(--ts-radius-sm);
  cursor: pointer;
  color: var(--ts-text-muted);
  transition: all var(--ts-transition);
}

.sidebar-toggle:hover {
  background: var(--ts-bg-hover);
  color: var(--ts-text-secondary);
}

/* ── Main Content ───────────────────────────────── */

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: var(--ts-space-xl) var(--ts-space-xl) var(--ts-space-lg);
  background: var(--ts-bg-primary);
}

/* ── Status Bar ─────────────────────────────────── */

.statusbar {
  height: var(--ts-statusbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--ts-space-md);
  background: var(--ts-bg-elevated);
  border-top: 1px solid var(--ts-border-color);
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  flex-shrink: 0;
  user-select: none;
}

.statusbar-left,
.statusbar-right {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
}

.status-icon {
  opacity: 0.6;
  flex-shrink: 0;
}

.status-item {
  white-space: nowrap;
}

.status-item.mono {
  font-family: var(--ts-font-display);
  font-size: 10.5px;
  letter-spacing: -0.2px;
}

.status-dot {
  width: 3px;
  height: 3px;
  border-radius: var(--ts-radius-full);
  background: var(--ts-border-color);
  flex-shrink: 0;
}
</style>
