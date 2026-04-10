<script setup lang="ts">
/**
 * DropZone — 全屏拖拽上传 & 粘贴入口。
 * ThreadScope 的首页，也是用户旅程的起点。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores/analysisStore'

const router = useRouter()
const store = useAnalysisStore()

const isDragging = ref(false)
const isUploading = ref(false)
const showPasteModal = ref(false)
const pasteContent = ref('')
const errorMsg = ref('')
const fileInput = ref<HTMLInputElement>()

async function handleFileDrop(e: DragEvent) {
  isDragging.value = false
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    await analyzeFile(files[0])
  }
}

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files && input.files.length > 0) {
    await analyzeFile(input.files[0])
  }
}

async function analyzeFile(file: File) {
  isUploading.value = true
  errorMsg.value = ''
  try {
    await store.uploadFile(file)
    router.push(`/analysis/${store.analysisId}/dashboard`)
  } catch (e: any) {
    errorMsg.value = e.message || 'Failed to analyze file'
  } finally {
    isUploading.value = false
  }
}

async function handlePaste() {
  if (!pasteContent.value.trim()) return
  isUploading.value = true
  errorMsg.value = ''
  try {
    await store.pasteContent(pasteContent.value)
    router.push(`/analysis/${store.analysisId}/dashboard`)
  } catch (e: any) {
    errorMsg.value = e.message || 'Failed to analyze pasted content'
  } finally {
    isUploading.value = false
    showPasteModal.value = false
  }
}
</script>

<template>
  <div class="dropzone-page">
    <!-- Background decoration -->
    <div class="bg-grid"></div>

    <!-- Main content -->
    <div class="dropzone-content">
      <!-- Logo & Title -->
      <div class="brand animate-card-enter">
        <div class="logo-icon">
          <svg width="48" height="48" viewBox="0 0 64 64" fill="none">
            <rect x="2" y="2" width="60" height="60" rx="14" stroke="#2563eb" stroke-width="2" fill="#eff6ff"/>
            <path d="M20 20 L32 44 L44 20" stroke="#2563eb" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
            <circle cx="32" cy="16" r="2.5" fill="#2563eb"/>
            <circle cx="20" cy="20" r="1.8" fill="#16a34a"/>
            <circle cx="44" cy="20" r="1.8" fill="#16a34a"/>
            <circle cx="32" cy="44" r="2.5" fill="#dc2626"/>
          </svg>
        </div>
        <h1 class="brand-title">ThreadScope</h1>
        <p class="brand-subtitle">Java Thread Dump Intelligent Analyzer</p>
      </div>

      <!-- Drop Area -->
      <div
        class="drop-area animate-card-enter"
        :class="{ 'drop-area--dragging': isDragging, 'drop-area--uploading': isUploading }"
        @dragover.prevent="isDragging = true"
        @dragleave="isDragging = false"
        @drop.prevent="handleFileDrop"
        @click="fileInput?.click()"
      >
        <input
          ref="fileInput"
          type="file"
          accept=".txt,.log,.tdump,.dump,.out"
          style="display: none"
          @change="handleFileSelect"
        />

        <div v-if="isUploading" class="drop-uploading">
          <div class="spinner"></div>
          <span>Analyzing...</span>
        </div>
        <div v-else class="drop-idle">
          <div class="drop-icon">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="17 8 12 3 7 8"/>
              <line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
          </div>
          <p class="drop-title">Drop Thread Dump File Here</p>
          <p class="drop-hint">or click to browse &middot; .txt .log .tdump .dump</p>
        </div>
      </div>

      <!-- Alternative actions -->
      <div class="alt-actions animate-card-enter">
        <button class="alt-btn" @click="showPasteModal = true">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>
            <rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>
          </svg>
          Paste Dump Text
        </button>
      </div>

      <!-- Error message -->
      <div v-if="errorMsg" class="error-banner animate-card-enter">
        {{ errorMsg }}
      </div>
    </div>

    <!-- Paste Modal -->
    <Teleport to="body">
      <div v-if="showPasteModal" class="modal-overlay" @click.self="showPasteModal = false">
        <div class="paste-modal animate-card-enter">
          <h3>Paste Thread Dump</h3>
          <textarea
            v-model="pasteContent"
            placeholder='Paste "Full thread dump..." content here...'
            rows="16"
          ></textarea>
          <div class="modal-actions">
            <button class="btn-secondary" @click="showPasteModal = false">Cancel</button>
            <button class="btn-primary" @click="handlePaste" :disabled="!pasteContent.trim()">
              Analyze
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.dropzone-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background: var(--ts-bg-primary);
}

.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(37, 99, 235, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(37, 99, 235, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
}

.dropzone-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ts-space-lg);
  max-width: 560px;
  width: 100%;
  padding: var(--ts-space-xl);
}

.brand {
  text-align: center;
}

.logo-icon {
  margin-bottom: var(--ts-space-md);
}

.brand-title {
  font-family: var(--ts-font-ui);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--ts-text-primary);
}

.brand-subtitle {
  color: var(--ts-text-muted);
  font-size: var(--ts-font-size-md);
  margin-top: var(--ts-space-xs);
}

.drop-area {
  width: 100%;
  border: 2px dashed #d1d5db;
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-2xl) var(--ts-space-xl);
  cursor: pointer;
  transition: all var(--ts-transition);
  background: var(--ts-bg-surface);
  text-align: center;
}

.drop-area:hover {
  border-color: var(--ts-accent);
  background: #fafbff;
}

.drop-area--dragging {
  border-color: var(--ts-accent);
  background: #eff6ff;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.1);
}

.drop-icon {
  color: var(--ts-text-muted);
  margin-bottom: var(--ts-space-md);
}

.drop-title {
  font-size: var(--ts-font-size-lg);
  font-weight: 600;
  color: var(--ts-text-primary);
}

.drop-hint {
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-muted);
  margin-top: var(--ts-space-xs);
}

.drop-uploading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ts-space-sm);
  color: var(--ts-accent);
  font-weight: 500;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--ts-border-color);
  border-top-color: var(--ts-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.alt-actions {
  display: flex;
  gap: var(--ts-space-md);
}

.alt-btn {
  display: flex;
  align-items: center;
  gap: var(--ts-space-sm);
  padding: var(--ts-space-sm) var(--ts-space-md);
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  background: var(--ts-bg-surface);
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-sm);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.alt-btn:hover {
  border-color: var(--ts-accent);
  color: var(--ts-accent);
}

.error-banner {
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: var(--ts-radius-md);
  color: var(--ts-danger);
  font-size: var(--ts-font-size-sm);
}

/* ── Modal ── */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  backdrop-filter: blur(4px);
}

.paste-modal {
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-lg);
  padding: var(--ts-space-lg);
  width: 90%;
  max-width: 640px;
  box-shadow: var(--ts-shadow-lg);
}

.paste-modal h3 {
  font-size: var(--ts-font-size-lg);
  margin-bottom: var(--ts-space-md);
}

.paste-modal textarea {
  width: 100%;
  background: var(--ts-bg-primary);
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  padding: var(--ts-space-md);
  color: var(--ts-text-primary);
  font-family: var(--ts-font-display);
  font-size: var(--ts-font-size-sm);
  resize: vertical;
  outline: none;
}

.paste-modal textarea:focus {
  border-color: var(--ts-accent);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ts-space-sm);
  margin-top: var(--ts-space-md);
}

.btn-primary {
  padding: var(--ts-space-sm) var(--ts-space-lg);
  background: var(--ts-accent);
  color: #fff;
  border: none;
  border-radius: var(--ts-radius-md);
  font-weight: 600;
  cursor: pointer;
  transition: all var(--ts-transition);
}

.btn-primary:hover { opacity: 0.9; }
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }

.btn-secondary {
  padding: var(--ts-space-sm) var(--ts-space-lg);
  background: transparent;
  color: var(--ts-text-secondary);
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  cursor: pointer;
  transition: all var(--ts-transition);
}

.btn-secondary:hover {
  border-color: var(--ts-text-secondary);
}
</style>
