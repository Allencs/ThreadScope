<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteJfrReport, fetchJfrReports, type JfrReportSummary } from '@/api/threadscope'
import { useAuthStore } from '@/stores/authStore'
import UserMenu from '@/components/common/UserMenu.vue'

const router = useRouter()
const auth = useAuthStore()

function canDelete(report: JfrReportSummary) {
  return auth.isAdmin || (report.createdBy != null && report.createdBy === auth.user?.username)
}
const reports = ref<JfrReportSummary[]>([])
const errorMsg = ref('')
const loading = ref(true)

function formatTime(epoch: number | null): string {
  if (epoch == null) return '—'
  return new Date(epoch).toLocaleString()
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  const seconds = Math.round(ms / 1000)
  if (seconds < 120) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${seconds % 60} 秒`
}

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    reports.value = await fetchJfrReports()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function remove(report: JfrReportSummary) {
  if (!confirm(`删除「${report.filename}」？解析结果会从数据库里去掉。`)) return
  try {
    await deleteJfrReport(report.id)
    reports.value = reports.value.filter(item => item.id !== report.id)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="history-page">
    <header class="history-bar">
      <button class="back" type="button" @click="router.push('/')">← 首页</button>
      <div>
        <h1>分析历史</h1>
        <p class="sub">已保存的 JFR 解析结果</p>
      </div>
      <span class="spacer"></span>
      <button class="refresh" type="button" @click="load">刷新</button>
      <UserMenu />
    </header>

    <div class="history-body">
    <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
    <p v-else-if="loading" class="muted">正在加载…</p>
    <p v-else-if="reports.length === 0" class="muted">还没有 JFR 分析。在首页拖入一个 .jfr 文件即可。</p>

    <table v-else class="history-table">
      <thead>
        <tr>
          <th>文件</th>
          <th>录制开始</th>
          <th>时长</th>
          <th>样本</th>
          <th>线程 Dump</th>
          <th>保存于</th>
          <th>上传者</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="report in reports" :key="report.id">
          <td>
            <button class="name" @click="router.push(`/jfr/${report.id}`)">{{ report.filename }}</button>
            <div class="jvm">{{ report.jvmVersion || 'JVM 未知' }}</div>
          </td>
          <td class="mono">{{ formatTime(report.recordingStartEpochMs) }}</td>
          <td class="mono">{{ formatDuration(report.durationMillis) }}</td>
          <td class="mono">{{ report.sampleCount.toLocaleString() }}</td>
          <td class="mono">{{ report.threadDumpCount }}</td>
          <td class="mono">{{ formatTime(report.createdAtEpochMs) }}</td>
          <td>{{ report.createdBy || '—' }}</td>
          <td class="actions">
            <button @click="router.push(`/jfr/${report.id}`)">打开</button>
            <button v-if="canDelete(report)" class="danger" @click="remove(report)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    </div>
  </div>
</template>

<style scoped>
.history-page {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--ts-bg-primary);
  color: var(--ts-text-primary);
}
.history-bar {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 16px;
  padding: 16px 28px 14px;
  background: var(--ts-bg-secondary);
  border-bottom: var(--ts-border);
}
.history-bar h1 { font-size: 18px; font-weight: 650; margin: 0; }
.sub { margin: 2px 0 0; color: var(--ts-text-secondary); font-size: 12.5px; }
.back, .refresh, .actions button, .name {
  border: 0;
  background: transparent;
  color: var(--ts-text-secondary);
  cursor: pointer;
  font: inherit;
}
.back { align-self: center; font-size: 12.5px; }
.back:hover, .name:hover, .refresh:hover { color: var(--ts-accent); }
.refresh {
  border: var(--ts-border);
  background: var(--ts-bg-surface);
  color: var(--ts-text-primary);
  border-radius: 8px;
  padding: 6px 12px;
}
.history-body { flex: 1; min-height: 0; overflow: auto; padding: 20px 28px 48px; }
.spacer { flex: 1; }
.muted { color: var(--ts-text-secondary); }
.error { color: var(--ts-danger); margin: 16px 28px 0; }
.history-table { width: 100%; border-collapse: collapse; background: var(--ts-bg-surface); border: var(--ts-border); border-radius: 12px; overflow: hidden; }
.history-table th, .history-table td { text-align: left; padding: 12px 14px; border-bottom: var(--ts-border); vertical-align: top; font-size: 13px; }
.history-table th { color: var(--ts-text-secondary); font-weight: 600; font-size: 12px; background: var(--ts-bg-inset); }
.name { font-weight: 650; padding: 0; color: var(--ts-accent); }
.jvm { color: var(--ts-text-secondary); font-size: 12px; margin-top: 4px; max-width: 420px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.actions { white-space: nowrap; }
.danger { color: var(--ts-danger); margin-left: 8px; }
</style>
