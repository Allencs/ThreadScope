<script setup lang="ts">
/**
 * DropZone — 全屏拖拽上传 & 粘贴入口。
 * ThreadScope 的首页，也是用户旅程的起点。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'
import { useSettingsStore } from '@/stores/settingsStore'
import { fetchJfrReports, uploadJfr, type JfrReportSummary } from '@/api/threadscope'
import CodeSettingsModal from '@/components/settings/CodeSettingsModal.vue'
import UserMenu from '@/components/common/UserMenu.vue'

const router = useRouter()
const store = useAnalysisStore()
const settings = useSettingsStore()

/** 与后端 multipart 上限、nginx client_max_body_size 保持一致 */
const MAX_UPLOAD_BYTES = 50 * 1024 * 1024
const MAX_JFR_BYTES = 512 * 1024 * 1024

// dragenter/dragleave 会在进出子元素时反复触发，用计数器避免高亮态闪烁
const dragDepth = ref(0)
const isDragging = computed(() => dragDepth.value > 0)
const isUploading = ref(false)
const uploadKind = ref<'dump' | 'jfr'>('dump')
const uploadName = ref('')
const showPasteModal = ref(false)
const pasteContent = ref('')
const errorMsg = ref('')
const fileInput = ref<HTMLInputElement>()
const recent = ref<JfrReportSummary[]>([])

const pasteSize = computed(() => (pasteContent.value ? formatSize(new Blob([pasteContent.value]).size) : ''))
const uploading = computed(() => store.uploadProgress > 0 && store.uploadProgress < 100)
const stageLabel = computed(() => {
  if (uploading.value) return `上传中 ${store.uploadProgress}%`
  return uploadKind.value === 'jfr' ? '正在解析 JFR：聚合采样、整理线程、抽取线程 Dump…' : '正在分析线程 Dump…'
})

onMounted(async () => {
  try {
    recent.value = (await fetchJfrReports()).slice(0, 4)
  } catch {
    recent.value = []
  }
})

async function handleFileDrop(e: DragEvent) {
  dragDepth.value = 0
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    await analyzeFiles(Array.from(files))
  }
}

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    await analyzeFiles(Array.from(input.files))
  }
  input.value = ''
}

async function analyzeFiles(files: File[]) {
  if (isUploading.value) return
  const jfrs = files.filter(file => file.name.toLowerCase().endsWith('.jfr'))
  if (jfrs.length > 0) {
    if (files.length !== 1) {
      errorMsg.value = '一次只能上传一个 JFR 文件'
      return
    }
    if (jfrs[0].size > MAX_JFR_BYTES) {
      errorMsg.value = `JFR 文件 ${formatSize(jfrs[0].size)}，超过 512MB 上限`
      return
    }
    uploadKind.value = 'jfr'
    uploadName.value = jfrs[0].name
    store.uploadProgress = 0
    isUploading.value = true
    errorMsg.value = ''
    try {
      const report = await uploadJfr(jfrs[0], (percent) => { store.uploadProgress = percent })
      router.push(`/jfr/${report.id}`)
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : 'JFR 解析失败'
    } finally {
      isUploading.value = false
    }
    return
  }
  const totalSize = files.reduce((sum, f) => sum + f.size, 0)
  if (totalSize > MAX_UPLOAD_BYTES) {
    errorMsg.value = `文件共 ${formatSize(totalSize)}，超过 50MB 上限`
    return
  }
  uploadKind.value = 'dump'
  uploadName.value = files.length === 1 ? files[0].name : `${files.length} 个 Dump 文件`
  store.uploadProgress = 0
  isUploading.value = true
  errorMsg.value = ''
  try {
    await store.uploadFile(files)
    router.push(`/analysis/${store.analysisId}/dashboard`)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '分析失败'
  } finally {
    isUploading.value = false
  }
}

async function handlePaste() {
  if (!pasteContent.value.trim()) return
  if (pasteContent.value.length > MAX_UPLOAD_BYTES) {
    errorMsg.value = '粘贴内容超过 50MB，请改用文件上传'
    return
  }
  uploadKind.value = 'dump'
  uploadName.value = '粘贴的文本'
  isUploading.value = true
  errorMsg.value = ''
  try {
    await store.pasteContent(pasteContent.value)
    // 仅成功时关闭弹窗；失败时保留内容和错误提示
    showPasteModal.value = false
    router.push(`/analysis/${store.analysisId}/dashboard`)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '分析失败'
  } finally {
    isUploading.value = false
  }
}

function browse() {
  if (!isUploading.value) fileInput.value?.click()
}

function formatSize(bytes: number): string {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(0)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDuration(ms: number): string {
  const seconds = Math.round(ms / 1000)
  if (seconds < 90) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 90) return `${minutes} 分`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

function ago(epoch: number): string {
  const minutes = Math.round((Date.now() - epoch) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.round(hours / 24)} 天前`
}
</script>

<template>
  <div
    class="dropzone-page"
    @dragover.prevent
    @dragenter.prevent="dragDepth++"
    @dragleave="dragDepth = Math.max(0, dragDepth - 1)"
    @drop.prevent="handleFileDrop"
  >
    <div class="bg-grid"></div>
    <div class="bg-glow glow-a"></div>
    <div class="bg-glow glow-b"></div>

    <header class="topnav">
      <div class="nav-brand">
        <svg width="28" height="28" viewBox="0 0 64 64" fill="none" aria-hidden="true">
          <rect x="2" y="2" width="60" height="60" rx="16" fill="url(#brandGrad)" />
          <path d="M20 21 L32 43 L44 21" stroke="#fff" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" fill="none" />
          <circle cx="32" cy="15" r="3" fill="#fff" />
          <defs>
            <linearGradient id="brandGrad" x1="0" y1="0" x2="64" y2="64">
              <stop offset="0" stop-color="#2563eb" />
              <stop offset="1" stop-color="#7c3aed" />
            </linearGradient>
          </defs>
        </svg>
        <span>ThreadScope</span>
      </div>
      <nav class="nav-links">
        <button type="button" @click="router.push('/jfr')">JFR 分析历史</button>
        <button type="button" @click="settings.settingsOpen = true">代码归属设置</button>
        <a href="/docs/jfr-advanced-manual.html" target="_blank" rel="noopener">JFR 手册 ↗</a>
        <UserMenu />
      </nav>
    </header>

    <main class="dropzone-content">
      <section class="hero animate-card-enter">
        <span class="eyebrow"><i></i>Java 线程诊断工作台</span>
        <h1>把线程 Dump 或 JFR 拖进来</h1>
        <p>多份 Dump 对比卡住的线程，JFR 直接看热点方法调用链、线程池的创建与销毁。</p>
      </section>

      <section
        class="drop-card animate-card-enter"
        :class="{ dragging: isDragging, busy: isUploading }"
        @click="browse"
      >
        <input
          ref="fileInput"
          type="file"
          multiple
          accept=".txt,.log,.tdump,.dump,.out,.json,.jfr"
          style="display: none"
          @change="handleFileSelect"
        />

        <div v-if="isUploading" class="progress-state" @click.stop>
          <div class="progress-icon" :class="uploadKind">{{ uploadKind === 'jfr' ? 'JFR' : 'TXT' }}</div>
          <div class="progress-body">
            <div class="progress-name" :title="uploadName">{{ uploadName }}</div>
            <div class="progress-track">
              <i :class="{ indeterminate: !uploading }" :style="uploading ? { width: `${store.uploadProgress}%` } : {}"></i>
            </div>
            <div class="progress-stage">{{ stageLabel }}</div>
          </div>
        </div>

        <template v-else>
          <div class="drop-main">
            <div class="drop-icon">
              <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 16V4" />
                <path d="M7 9l5-5 5 5" />
                <path d="M20 16v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2" />
              </svg>
            </div>
            <div class="drop-text">
              <h2>拖拽文件到这里，或 <span class="link">点击选择</span></h2>
              <p>文件类型自动识别，分析完直接进入结果页</p>
            </div>
            <div class="drop-actions" @click.stop>
              <button class="btn-primary" type="button" @click="browse">选择文件</button>
              <button class="btn-ghost" type="button" @click="showPasteModal = true">粘贴 Dump 文本</button>
            </div>
          </div>

          <div class="lanes">
            <div class="lane">
              <div class="lane-head"><span class="tag dump">线程 Dump</span><small>≤ 50MB · 可多选</small></div>
              <p>jstack / jcmd Thread.print / 带时间戳的日志。隔几秒抓 3 份一起拖入，自动做趋势与卡死线程对比。</p>
              <code title="点击选中，复制命令" @click.stop>jcmd &lt;pid&gt; Thread.dump_to_file -format=json threads.json</code>
            </div>
            <div class="lane">
              <div class="lane-head"><span class="tag jfr">JFR 录制</span><small>≤ 512MB · 单个 .jfr</small></div>
              <p>全局热点方法与调用者树、线程池创建/销毁趋势，录制里的 jdk.ThreadDump 自动抽出做多 Dump 对比。</p>
              <code title="点击选中，复制命令" @click.stop>jcmd &lt;pid&gt; JFR.start duration=120s filename=app.jfr</code>
            </div>
          </div>
        </template>
      </section>

      <Transition name="fade">
        <div v-if="errorMsg && !showPasteModal" class="error-banner">
          <span>{{ errorMsg }}</span>
          <button type="button" @click="errorMsg = ''">×</button>
        </div>
      </Transition>

      <section class="features animate-card-enter">
        <div class="feature">
          <span class="f-icon blue">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="3" y="4" width="7" height="16" rx="2" /><rect x="14" y="4" width="7" height="16" rx="2" /><path d="M10 12h4" /></svg>
          </span>
          <div>
            <b>多 Dump 对比</b>
            <p>线程状态变化、一直不动的线程、锁等待链</p>
          </div>
        </div>
        <div class="feature">
          <span class="f-icon pink">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h16M7 12h10M10 18h4" /></svg>
          </span>
          <div>
            <b>JFR 热点方法</b>
            <p>self / inclusive 占比，逐层展开调用者，支持搜索</p>
          </div>
        </div>
        <div class="feature">
          <span class="f-icon green">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 17l5-5 4 3 8-8" /><path d="M15 7h5v5" /></svg>
          </span>
          <div>
            <b>线程生命周期</b>
            <p>按线程池归类，创建与销毁的时间线和启动栈</p>
          </div>
        </div>
      </section>

      <section v-if="recent.length" class="recent animate-card-enter">
        <div class="recent-head">
          <h3>最近的 JFR 分析</h3>
          <button type="button" @click="router.push('/jfr')">全部历史 →</button>
        </div>
        <button
          v-for="report in recent"
          :key="report.id"
          type="button"
          class="recent-row"
          @click="router.push(`/jfr/${report.id}`)"
        >
          <span class="r-badge">JFR</span>
          <span class="r-name" :title="report.filename">{{ report.filename }}</span>
          <span class="r-meta">{{ formatDuration(report.durationMillis) }} · {{ report.sampleCount.toLocaleString() }} 样本 · {{ report.threadDumpCount }} Dump</span>
          <span class="r-time">{{ ago(report.createdAtEpochMs) }}</span>
        </button>
      </section>
    </main>

    <Transition name="fade">
      <div v-if="isDragging" class="drag-overlay">
        <div class="drag-box">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 4v12" /><path d="M7 11l5 5 5-5" /><path d="M20 16v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2" />
          </svg>
          <b>松开即可开始分析</b>
          <span>线程 Dump（可多个）或单个 .jfr</span>
        </div>
      </div>
    </Transition>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="showPasteModal" class="modal-overlay" @click.self="showPasteModal = false">
          <div class="paste-modal">
            <div class="modal-head">
              <h3>粘贴线程 Dump</h3>
              <button type="button" class="close" @click="showPasteModal = false">×</button>
            </div>
            <p class="modal-hint">粘贴 jstack 输出（以 “Full thread dump” 开头）或 jcmd Thread.print 的内容。</p>
            <textarea
              v-model="pasteContent"
              placeholder="Full thread dump OpenJDK 64-Bit Server VM ..."
              rows="16"
            ></textarea>
            <div v-if="errorMsg" class="error-banner inline">{{ errorMsg }}</div>
            <div class="modal-actions">
              <span class="modal-size">{{ pasteSize }}</span>
              <button class="btn-ghost" type="button" @click="showPasteModal = false">取消</button>
              <button class="btn-primary" type="button" :disabled="!pasteContent.trim() || isUploading" @click="handlePaste">
                {{ isUploading ? '分析中…' : '开始分析' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <CodeSettingsModal />
  </div>
</template>

<style scoped>
.dropzone-page {
  height: 100vh;
  position: relative;
  overflow-x: hidden;
  overflow-y: auto;
  background: linear-gradient(180deg, #f6f8fc 0%, #f8f9fb 60%);
  color: var(--ts-text-primary);
}

.bg-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(37, 99, 235, 0.13) 1px, transparent 1px);
  background-size: 22px 22px;
  mask-image: radial-gradient(ellipse 70% 55% at 50% 30%, #000 30%, transparent 100%);
  pointer-events: none;
}
.bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  opacity: 0.55;
  animation: drift 18s ease-in-out infinite alternate;
}
.glow-a { width: 520px; height: 520px; left: 8%; top: -180px; background: #bfdbfe; }
.glow-b { width: 460px; height: 460px; right: 6%; top: 60px; background: #ddd6fe; animation-delay: -6s; }
@keyframes drift { to { transform: translate(40px, 30px) scale(1.08); } }

.topnav {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1080px;
  margin: 0 auto;
  padding: 18px 28px;
}
.nav-brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; letter-spacing: -0.01em; }
.nav-links { display: flex; align-items: center; gap: 4px; }
.nav-links button, .nav-links a {
  border: 0;
  background: transparent;
  padding: 7px 12px;
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
  color: var(--ts-text-secondary);
  text-decoration: none;
  cursor: pointer;
  transition: background var(--ts-transition), color var(--ts-transition);
}
.nav-links button:hover, .nav-links a:hover { background: rgba(255, 255, 255, 0.8); color: var(--ts-text-primary); }

.dropzone-content {
  position: relative;
  z-index: 1;
  max-width: 880px;
  margin: 0 auto;
  padding: 28px 28px 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero { text-align: center; margin-bottom: 6px; }
.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #e0e7ff;
  color: #4338ca;
  font-size: 12px;
  font-weight: 600;
}
.eyebrow i { width: 6px; height: 6px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.18); }
.hero h1 {
  margin: 16px 0 10px;
  font-size: 34px;
  font-weight: 750;
  letter-spacing: -0.03em;
  background: linear-gradient(90deg, #0f172a 0%, #1e3a8a 55%, #6d28d9 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.hero p { margin: 0; color: var(--ts-text-secondary); font-size: 14.5px; }

.drop-card {
  position: relative;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e3e8f0;
  border-radius: 20px;
  padding: 26px 28px 24px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 12px 32px -12px rgba(30, 58, 138, 0.18);
  cursor: pointer;
  transition: border-color var(--ts-transition-slow), box-shadow var(--ts-transition-slow), transform var(--ts-transition-slow);
  backdrop-filter: blur(6px);
}
.drop-card::before {
  content: '';
  position: absolute;
  inset: 10px;
  border: 1.5px dashed #cdd6e4;
  border-radius: 14px;
  pointer-events: none;
  transition: border-color var(--ts-transition-slow), background var(--ts-transition-slow);
}
.drop-card:hover { border-color: #c7d4f5; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 18px 40px -14px rgba(37, 99, 235, 0.28); }
.drop-card:hover::before, .drop-card.dragging::before { border-color: #93b4f5; }
.drop-card.dragging { transform: scale(1.01); border-color: var(--ts-accent); }
.drop-card.dragging::before { background: rgba(239, 246, 255, 0.6); }
.drop-card.busy { cursor: default; }

.drop-main { position: relative; display: flex; align-items: center; gap: 18px; padding: 10px 8px 18px; }
.drop-icon {
  flex: none;
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  color: var(--ts-accent);
  background: linear-gradient(135deg, #eff6ff, #f5f3ff);
  border: 1px solid #dbe4ff;
  transition: transform var(--ts-transition-slow);
}
.drop-card:hover .drop-icon { transform: translateY(-3px); }
.drop-card:hover .drop-icon svg { animation: bob 1.2s ease-in-out infinite; }
@keyframes bob { 50% { transform: translateY(-2px); } }
.drop-text { flex: 1; min-width: 0; }
.drop-text h2 { margin: 0; font-size: 17px; font-weight: 650; }
.drop-text .link { color: var(--ts-accent); }
.drop-text p { margin: 5px 0 0; color: var(--ts-text-secondary); font-size: 13px; }
.drop-actions { display: flex; gap: 8px; flex: none; }

.lanes { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; padding: 0 8px 4px; }
.lane { padding: 14px 16px; border-radius: 12px; background: #f8fafd; border: 1px solid #edf1f7; }
.lane-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.lane-head small { color: var(--ts-text-muted); font-size: 11.5px; }
.tag { font-size: 11.5px; font-weight: 700; padding: 2px 9px; border-radius: 999px; }
.tag.dump { background: #dbeafe; color: #1d4ed8; }
.tag.jfr { background: #ede9fe; color: #6d28d9; }
.lane p { margin: 0 0 10px; font-size: 12.5px; line-height: 1.65; color: var(--ts-text-secondary); }
.lane code {
  display: block;
  font-family: var(--ts-font-display);
  font-size: 11.5px;
  color: #334155;
  background: #fff;
  border: 1px solid #e5e9f0;
  border-radius: 7px;
  padding: 6px 9px;
  line-height: 1.5;
  word-break: break-all;
  user-select: all;
}

.progress-state { display: flex; align-items: center; gap: 18px; padding: 26px 14px; }
.progress-icon {
  flex: none;
  width: 56px;
  height: 56px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, #2563eb, #3b82f6);
  box-shadow: 0 6px 16px -6px rgba(37, 99, 235, 0.55);
}
.progress-icon.jfr { background: linear-gradient(135deg, #2563eb, #7c3aed); }
.progress-body { flex: 1; min-width: 0; }
.progress-name { font-weight: 650; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.progress-track { position: relative; height: 8px; border-radius: 999px; background: #e8edf5; overflow: hidden; margin: 10px 0 8px; }
.progress-track i { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 999px; background: linear-gradient(90deg, #2563eb, #7c3aed); transition: width 200ms ease; }
.progress-track i.indeterminate { width: 35%; animation: slide 1.3s ease-in-out infinite; }
@keyframes slide { from { left: -35%; } to { left: 100%; } }
.progress-stage { font-size: 12.5px; color: var(--ts-text-secondary); }

.error-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 10px;
  color: #b91c1c;
  font-size: 13px;
}
.error-banner button { border: 0; background: transparent; color: inherit; font-size: 16px; cursor: pointer; line-height: 1; }
.error-banner.inline { margin-top: 10px; }

.features { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.feature {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.75);
  border: 1px solid #e8edf4;
  transition: transform var(--ts-transition-slow), box-shadow var(--ts-transition-slow);
}
.feature:hover { transform: translateY(-2px); box-shadow: var(--ts-shadow-md); }
.feature b { font-size: 13.5px; }
.feature p { margin: 3px 0 0; font-size: 12px; line-height: 1.55; color: var(--ts-text-secondary); }
.f-icon { flex: none; width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; }
.f-icon svg { width: 18px; height: 18px; }
.f-icon.blue { background: #dbeafe; color: #1d4ed8; }
.f-icon.pink { background: #fce7f3; color: #be185d; }
.f-icon.green { background: #dcfce7; color: #15803d; }

.recent { background: rgba(255, 255, 255, 0.85); border: 1px solid #e8edf4; border-radius: 14px; padding: 12px 8px 8px; }
.recent-head { display: flex; align-items: center; justify-content: space-between; padding: 0 10px 6px; }
.recent-head h3 { margin: 0; font-size: 13.5px; font-weight: 650; }
.recent-head button { border: 0; background: transparent; color: var(--ts-accent); font: inherit; font-size: 12.5px; cursor: pointer; }
.recent-row {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--ts-transition);
}
.recent-row:hover { background: #f1f5fb; }
.r-badge { font-size: 10px; font-weight: 800; color: #fff; background: linear-gradient(135deg, #2563eb, #7c3aed); border-radius: 6px; padding: 3px 6px; }
.r-name { font-size: 13px; font-weight: 550; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.r-meta { font-size: 12px; color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; white-space: nowrap; }
.r-time { font-size: 12px; color: var(--ts-text-muted); white-space: nowrap; min-width: 64px; text-align: right; }

.btn-primary {
  padding: 8px 16px;
  background: var(--ts-accent);
  color: #fff;
  border: none;
  border-radius: 9px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.28);
  transition: filter var(--ts-transition), transform var(--ts-transition);
}
.btn-primary:hover { filter: brightness(1.07); }
.btn-primary:active { transform: translateY(1px); }
.btn-primary:disabled { opacity: 0.45; cursor: not-allowed; box-shadow: none; }
.btn-ghost {
  padding: 8px 14px;
  background: #fff;
  color: var(--ts-text-primary);
  border: var(--ts-border);
  border-radius: 9px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color var(--ts-transition), color var(--ts-transition);
}
.btn-ghost:hover { border-color: var(--ts-accent); color: var(--ts-accent); }

.drag-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(239, 246, 255, 0.72);
  backdrop-filter: blur(3px);
  pointer-events: none;
}
.drag-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 36px 56px;
  border-radius: 20px;
  border: 2px dashed var(--ts-accent);
  background: rgba(255, 255, 255, 0.9);
  color: var(--ts-accent);
  box-shadow: var(--ts-shadow-lg);
  animation: pop 220ms cubic-bezier(.2,.8,.2,1);
}
.drag-box b { font-size: 16px; color: var(--ts-text-primary); }
.drag-box span { font-size: 12.5px; color: var(--ts-text-secondary); }
@keyframes pop { from { transform: scale(0.94); opacity: 0; } }

.fade-enter-active, .fade-leave-active { transition: opacity 180ms ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  backdrop-filter: blur(4px);
}
.paste-modal {
  background: var(--ts-bg-surface);
  border-radius: 16px;
  padding: 20px 22px;
  width: 90%;
  max-width: 720px;
  box-shadow: 0 24px 60px -20px rgba(15, 23, 42, 0.35);
  animation: pop 220ms cubic-bezier(.2,.8,.2,1);
}
.modal-head { display: flex; align-items: center; justify-content: space-between; }
.modal-head h3 { margin: 0; font-size: 16px; }
.close { border: 0; background: var(--ts-bg-inset); width: 28px; height: 28px; border-radius: 8px; font-size: 18px; color: var(--ts-text-secondary); cursor: pointer; }
.close:hover { color: var(--ts-text-primary); }
.modal-hint { margin: 6px 0 12px; font-size: 12.5px; color: var(--ts-text-secondary); }
.paste-modal textarea {
  width: 100%;
  background: #f8fafc;
  border: var(--ts-border);
  border-radius: 10px;
  padding: 12px 14px;
  color: var(--ts-text-primary);
  font-family: var(--ts-font-display);
  font-size: 12.5px;
  line-height: 1.6;
  resize: vertical;
  outline: none;
  transition: border-color var(--ts-transition), box-shadow var(--ts-transition);
}
.paste-modal textarea:focus { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); background: #fff; }
.modal-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.modal-size { margin-right: auto; font-size: 12px; color: var(--ts-text-muted); }

@media (max-width: 760px) {
  .drop-main { flex-wrap: wrap; }
  .lanes, .features { grid-template-columns: 1fr; }
  .hero h1 { font-size: 27px; }
}
</style>
