<script setup lang="ts">
/**
 * CodeSettingsModal — 按应用配置"代码归属"：
 * - 业务包前缀 (区分业务代码与三方框架)
 * - 源码仓库链接模板 (栈帧跳转源码)
 * 配置保存在浏览器本地，上传时随请求带给后端用于诊断中的"业务调用点"定位。
 */
import { computed, ref, watch } from 'vue'
import { useSettingsStore, type AppProfile } from '@/stores/settingsStore'
import { useAnalysisStore } from '@/stores/analysisStore'
import { SOURCE_LINK_PRESETS, type SourceLinkRule } from '@/utils/sourceLink'

const settings = useSettingsStore()
const analysis = useAnalysisStore()

const draft = ref<AppProfile | null>(null)
const packagesText = ref('')
const newProfileName = ref('')

function loadDraft() {
  const p = settings.activeProfile
  draft.value = {
    ...p,
    businessPackages: [...p.businessPackages],
    sourceLinks: p.sourceLinks.map(r => ({ ...r })),
  }
  packagesText.value = p.businessPackages.join('\n')
}

watch(() => [settings.settingsOpen, settings.activeProfileId], ([open]) => {
  if (open) loadDraft()
}, { immediate: true })

const parsedPackages = computed(() =>
  [...new Set(packagesText.value.split(/[\s,;]+/).map(s => s.trim()).filter(s => /^[\w.$]+$/.test(s)))],
)

const suggestions = computed(() => {
  const existing = parsedPackages.value
  return (analysis.overview?.suggestedBusinessPackages ?? [])
    .filter(s => !existing.some(p => (s + '.').startsWith(p.endsWith('.') ? p : p + '.')))
})

function addSuggestion(pkg: string) {
  packagesText.value = [...parsedPackages.value, pkg].join('\n')
}

function addRule(template = '') {
  draft.value?.sourceLinks.push({ prefix: '', template })
}

function removeRule(rule: SourceLinkRule) {
  if (!draft.value) return
  draft.value.sourceLinks = draft.value.sourceLinks.filter(r => r !== rule)
}

function applyPreset(rule: SourceLinkRule, ev: Event) {
  const value = (ev.target as HTMLSelectElement).value
  if (value) rule.template = value
  ;(ev.target as HTMLSelectElement).value = ''
}

function switchProfile(id: string) {
  save(false)
  settings.activeProfileId = id
}

function createProfile() {
  const name = newProfileName.value.trim()
  if (!name) return
  save(false)
  settings.createProfile(name)
  newProfileName.value = ''
}

function deleteProfile() {
  if (!draft.value || settings.profiles.length <= 1) return
  if (!confirm(`删除应用配置「${draft.value.name}」？`)) return
  settings.deleteProfile(draft.value.id)
}

function save(close = true) {
  if (!draft.value) return
  settings.saveProfile({
    ...draft.value,
    name: draft.value.name.trim() || '未命名应用',
    businessPackages: parsedPackages.value,
    sourceLinks: draft.value.sourceLinks.filter(r => r.template.trim()),
  })
  if (close) settings.settingsOpen = false
}

function close() {
  settings.settingsOpen = false
}
</script>

<template>
  <Teleport to="body">
    <div v-if="settings.settingsOpen && draft" class="modal-overlay" @click.self="close">
      <div class="settings-modal">
        <header class="settings-modal__header">
          <h3>代码归属设置</h3>
          <button class="icon-btn" title="关闭" @click="close">×</button>
        </header>

        <section class="settings-section">
          <div class="section-label">应用</div>
          <div class="profile-row">
            <select class="input" :value="draft.id" @change="switchProfile(($event.target as HTMLSelectElement).value)">
              <option v-for="p in settings.profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
            <input v-model="draft.name" class="input" placeholder="应用名称" />
            <button class="btn btn--ghost" :disabled="settings.profiles.length <= 1" @click="deleteProfile">删除</button>
          </div>
          <div class="profile-row">
            <input v-model="newProfileName" class="input" placeholder="新应用名称，如 order-service" @keyup.enter="createProfile" />
            <button class="btn" :disabled="!newProfileName.trim()" @click="createProfile">新建应用</button>
          </div>
          <p class="section-hint">上传 dump 时使用当前选中的应用配置。</p>
        </section>

        <section class="settings-section">
          <div class="section-label">业务包前缀</div>
          <textarea
            v-model="packagesText"
            class="input textarea mono"
            rows="4"
            placeholder="每行一个，如&#10;com.acme.order&#10;com.acme.common"
          ></textarea>
          <div v-if="suggestions.length" class="suggestions">
            <span class="section-hint">本次 dump 中检测到的候选业务包：</span>
            <button v-for="s in suggestions" :key="s" class="chip mono" @click="addSuggestion(s)">+ {{ s }}</button>
          </div>
          <p class="section-hint">
            匹配的类视为业务代码 (优先于框架列表)。留空时，JDK 与已知框架之外的代码都按业务代码推断。
            修改后栈帧高亮立即生效；诊断结论中的"业务调用点"在下次上传时生效。
          </p>
        </section>

        <section class="settings-section">
          <div class="section-label">源码跳转</div>
          <div v-for="(rule, i) in draft.sourceLinks" :key="i" class="rule-row">
            <input v-model="rule.prefix" class="input input--prefix mono" placeholder="包前缀 (留空=全部业务代码)" />
            <input v-model="rule.template" class="input mono" placeholder="https://gitlab.example.com/.../src/main/java/{path}#L{line}" />
            <select class="input input--preset" @change="applyPreset(rule, $event)">
              <option value="">模板…</option>
              <option v-for="p in SOURCE_LINK_PRESETS" :key="p.label" :value="p.template">{{ p.label }}</option>
            </select>
            <button class="icon-btn" title="删除" @click="removeRule(rule)">×</button>
          </div>
          <button class="btn btn--ghost" @click="addRule()">+ 添加规则</button>
          <p class="section-hint">
            占位符：<code>{path}</code> 包路径/文件名 · <code>{file}</code> · <code>{line}</code> · <code>{class}</code> ·
            <code>{package}</code> · <code>{method}</code>。多模块项目可按包前缀分别配置，最长前缀优先。
          </p>
        </section>

        <footer class="settings-modal__footer">
          <button class="btn btn--ghost" @click="close">取消</button>
          <button class="btn btn--primary" @click="save()">保存</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.settings-modal {
  width: min(760px, 94vw);
  max-height: 88vh;
  overflow: auto;
  background: var(--ts-bg-surface);
  border-radius: var(--ts-radius-lg);
  box-shadow: var(--ts-shadow-lg);
  font-family: var(--ts-font-ui);
}

.settings-modal__header,
.settings-modal__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--ts-space-md) var(--ts-space-lg);
}
.settings-modal__header { border-bottom: var(--ts-border); }
.settings-modal__header h3 { font-size: var(--ts-font-size-lg); font-weight: 600; }
.settings-modal__footer { border-top: var(--ts-border); justify-content: flex-end; gap: var(--ts-space-sm); }

.settings-section {
  padding: var(--ts-space-md) var(--ts-space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--ts-space-sm);
}
.settings-section + .settings-section { border-top: 1px dashed var(--ts-border-color); }

.section-label {
  font-size: var(--ts-font-size-xs);
  font-weight: 600;
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-hint {
  font-size: var(--ts-font-size-xs);
  color: var(--ts-text-muted);
  line-height: 1.6;
}
.section-hint code {
  font-family: var(--ts-font-display);
  background: var(--ts-bg-inset);
  padding: 0 4px;
  border-radius: var(--ts-radius-xs);
}

.profile-row,
.rule-row {
  display: flex;
  gap: var(--ts-space-sm);
  align-items: center;
}

.input {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  font-size: var(--ts-font-size-sm);
  color: var(--ts-text-primary);
  outline: none;
}
.input:focus { border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.input--prefix { flex: 0 0 200px; }
.input--preset { flex: 0 0 110px; }
.textarea { resize: vertical; line-height: 1.6; }

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.chip {
  padding: 2px 10px;
  border: 1px dashed #93c5fd;
  border-radius: var(--ts-radius-full);
  background: #eff6ff;
  color: #1d4ed8;
  font-size: var(--ts-font-size-xs);
  cursor: pointer;
}
.chip:hover { border-style: solid; }

.btn {
  padding: 6px 14px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-sm);
  cursor: pointer;
  white-space: nowrap;
}
.btn:hover:not(:disabled) { border-color: var(--ts-accent); color: var(--ts-accent); }
.btn:disabled { opacity: 0.4; cursor: not-allowed; }
.btn--ghost { background: transparent; align-self: flex-start; }
.btn--primary { background: var(--ts-accent); border-color: var(--ts-accent); color: #fff; }
.btn--primary:hover:not(:disabled) { color: #fff; filter: brightness(1.08); }

.icon-btn {
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--ts-radius-full);
  background: transparent;
  color: var(--ts-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
}
.icon-btn:hover { background: var(--ts-bg-hover); color: var(--ts-text-primary); }
</style>
