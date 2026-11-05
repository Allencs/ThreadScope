<script setup lang="ts">
/**
 * StackTraceView — 统一的堆栈渲染组件。
 *
 * - 栈帧按 JDK / 三方框架 / 业务代码三层着色，业务帧高亮
 * - 连续的非业务帧 (不含锁操作、不在栈顶) 折叠为一行，可逐段展开
 * - 业务帧按应用配置的源码仓库模板生成跳转链接
 * - 锁操作行内嵌在对应栈帧之后，点击跳转到锁分析页
 */
import { computed, onMounted, ref, watch } from 'vue'
import type { CodeLayer, LockAction, LockInfo, StackFrame } from '@/types'
import { useSettingsStore } from '@/stores/settingsStore'
import { frameworkLabel } from '@/utils/codeLayer'
import { buildSourceUrl, findRule, frameReference } from '@/utils/sourceLink'

const props = withDefaults(defineProps<{
  frames: StackFrame[]
  lockActions?: LockAction[]
  lockMap?: Record<string, LockInfo>
  hideHeader?: boolean
}>(), {
  lockActions: () => [],
  lockMap: () => ({}),
  hideHeader: false,
})

const emit = defineEmits<{ (e: 'navigate-lock', lockAddress: string): void }>()

const settings = useSettingsStore()
onMounted(() => settings.loadCodeLayerConfig())

/** 栈顶始终可见的帧数 — 线程"停在哪里"是最重要的信息 */
const ALWAYS_VISIBLE_TOP = 2
/** 连续多少个非业务帧才折叠 */
const MIN_FOLD_RUN = 3

const layers = computed<CodeLayer[]>(() => props.frames.map(f => settings.classifier(f.className)))

const layerCounts = computed(() => {
  const c: Record<CodeLayer, number> = { BUSINESS: 0, FRAMEWORK: 0, JDK: 0 }
  for (const l of layers.value) c[l]++
  return c
})

const locksByFrame = computed(() => {
  const m = new Map<number, LockAction[]>()
  for (const la of props.lockActions) {
    const list = m.get(la.frameIndex) ?? []
    list.push(la)
    m.set(la.frameIndex, list)
  }
  return m
})

const expandedRuns = ref<Set<number>>(new Set())
watch(() => props.frames, () => { expandedRuns.value = new Set() })

interface FrameItem { type: 'frame'; idx: number }
interface FoldItem { type: 'fold'; start: number; end: number; summary: string }
type Item = FrameItem | FoldItem

function isFoldable(i: number): boolean {
  return i >= ALWAYS_VISIBLE_TOP && layers.value[i] !== 'BUSINESS' && !locksByFrame.value.has(i)
}

function summarize(start: number, end: number): string {
  const counts = new Map<string, number>()
  for (let i = start; i < end; i++) {
    const label = layers.value[i] === 'JDK' ? 'JDK' : frameworkLabel(props.frames[i].className)
    counts.set(label, (counts.get(label) ?? 0) + 1)
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([k, v]) => `${k} ×${v}`)
    .join(' · ')
}

const items = computed<Item[]>(() => {
  const n = props.frames.length
  if (!settings.foldFrameworkFrames) {
    return props.frames.map((_, idx) => ({ type: 'frame', idx }))
  }
  const out: Item[] = []
  let i = 0
  while (i < n) {
    if (!isFoldable(i)) {
      out.push({ type: 'frame', idx: i })
      i++
      continue
    }
    let j = i
    while (j < n && isFoldable(j)) j++
    if (j - i >= MIN_FOLD_RUN && !expandedRuns.value.has(i)) {
      out.push({ type: 'fold', start: i, end: j, summary: summarize(i, j) })
    } else {
      for (let k = i; k < j; k++) out.push({ type: 'frame', idx: k })
    }
    i = j
  }
  return out
})

const hasFolds = computed(() => items.value.some(it => it.type === 'fold'))

function expandRun(start: number) {
  const s = new Set(expandedRuns.value)
  s.add(start)
  expandedRuns.value = s
}

function sourceUrl(idx: number): string | null {
  const frame = props.frames[idx]
  const rules = settings.activeProfile.sourceLinks
  const rule = findRule(frame.className, rules)
  if (!rule) return null
  // 前缀留空的规则只作用于业务代码
  if (!rule.prefix.trim() && layers.value[idx] !== 'BUSINESS') return null
  return buildSourceUrl(frame, [rule])
}

function packageOf(cls: string): string {
  const i = cls.lastIndexOf('.')
  return i > 0 ? cls.slice(0, i + 1) : ''
}

function simpleClass(cls: string): string {
  const i = cls.lastIndexOf('.')
  return i > 0 ? cls.slice(i + 1) : cls
}

const copiedIdx = ref<number | null>(null)
async function copyFrame(idx: number) {
  try {
    await navigator.clipboard.writeText(frameReference(props.frames[idx]))
    copiedIdx.value = idx
    setTimeout(() => { if (copiedIdx.value === idx) copiedIdx.value = null }, 1200)
  } catch {
    // 剪贴板不可用 (非 https) 时静默
  }
}

function lockLabel(lock: LockAction): string {
  switch (lock.type) {
    case 'Held': return '- locked'
    case 'WaitingToLock': return '- waiting to lock'
    case 'ParkingToWaitFor': return '- parking to wait for'
    case 'WaitingOn': return '- waiting on'
    default: return '- lock operation'
  }
}

function lockClass(lock: LockAction): string {
  switch (lock.type) {
    case 'Held': return 'lock-action--held'
    case 'WaitingToLock': return 'lock-action--blocked'
    case 'ParkingToWaitFor': return 'lock-action--parking'
    case 'WaitingOn': return 'lock-action--waiting'
    default: return ''
  }
}

function contention(lock: LockAction): LockInfo | null {
  if (lock.type !== 'Held') return null
  const info = props.lockMap[lock.lockAddress]
  return info?.waitingThreadNames?.length ? info : null
}
</script>

<template>
  <div class="stack-view">
    <div v-if="!hideHeader" class="stack-view__header">
      <span class="stack-view__title">Stack Trace ({{ frames.length }} frames)</span>
      <span class="layer-chip layer-chip--business" title="业务代码帧">业务 {{ layerCounts.BUSINESS }}</span>
      <span class="layer-chip layer-chip--framework" title="三方框架 / 中间件帧">框架 {{ layerCounts.FRAMEWORK }}</span>
      <span class="layer-chip layer-chip--jdk" title="JDK 帧">JDK {{ layerCounts.JDK }}</span>
      <span
        v-if="settings.businessInferred"
        class="layer-hint"
        title="未配置业务包：JDK 与已知框架之外的代码按业务代码处理。可在「代码归属」中配置业务包前缀。"
      >推断</span>
      <span class="stack-view__spacer"></span>
      <label class="fold-toggle">
        <input v-model="settings.foldFrameworkFrames" type="checkbox" />
        折叠框架帧
      </label>
      <button class="stack-view__btn" title="配置业务包与源码仓库" @click="settings.settingsOpen = true">代码归属</button>
    </div>

    <div class="stack-view__body mono">
      <template v-for="item in items" :key="item.type === 'frame' ? 'f' + item.idx : 'r' + item.start">
        <!-- 折叠行 -->
        <div v-if="item.type === 'fold'" class="fold-row" @click="expandRun(item.start)" :title="'展开第 ' + item.start + '~' + (item.end - 1) + ' 帧'">
          <span class="frame-idx">⋯</span>
          <span class="fold-row__text">已折叠 {{ item.end - item.start }} 个框架帧</span>
          <span class="fold-row__summary">{{ item.summary }}</span>
          <span class="fold-row__action">展开</span>
        </div>

        <template v-else>
          <div
            class="frame"
            :class="'frame--' + layers[item.idx].toLowerCase()"
          >
            <span class="frame-idx">{{ item.idx }}</span>
            <span class="frame-at">at</span>
            <span class="frame-sig"><span class="frame-pkg">{{ packageOf(frames[item.idx].className) }}</span><span class="frame-class">{{ simpleClass(frames[item.idx].className) }}</span>.<span class="frame-method">{{ frames[item.idx].methodName }}</span>(<a
                v-if="sourceUrl(item.idx)"
                class="frame-source frame-source--link"
                :href="sourceUrl(item.idx)!"
                target="_blank"
                rel="noopener"
                :title="'打开源码: ' + sourceUrl(item.idx)"
                @click.stop
              >{{ frames[item.idx].source }}</a><span v-else class="frame-source">{{ frames[item.idx].source }}</span>)</span>
            <span v-if="layers[item.idx] === 'BUSINESS'" class="frame-badge">业务</span>
            <button class="frame-copy" :title="'复制 ' + frames[item.idx].className + '.' + frames[item.idx].methodName" @click.stop="copyFrame(item.idx)">
              {{ copiedIdx === item.idx ? '已复制' : '复制' }}
            </button>
          </div>

          <div
            v-for="(lock, li) in locksByFrame.get(item.idx) ?? []"
            :key="'l' + item.idx + '-' + li"
            class="lock-action"
            :class="[lockClass(lock), { 'lock-action--contention': contention(lock) }]"
            :title="'在锁分析页查看 ' + lock.lockAddress"
            @click.stop="emit('navigate-lock', lock.lockAddress)"
          >
            <span class="lock-type">{{ lockLabel(lock) }}</span>
            <span class="lock-target">&lt;<span class="lock-addr">{{ lock.lockAddress }}</span>&gt; (a {{ lock.lockClassName }})</span>
            <span v-if="contention(lock)" class="lock-contention-badge">
              阻塞 {{ contention(lock)!.waitingThreadNames.length }} 个线程
            </span>
            <span class="lock-goto">→</span>
          </div>
        </template>
      </template>

      <div v-if="hideHeader && hasFolds" class="fold-inline-toggle">
        <button class="stack-view__btn" @click="settings.foldFrameworkFrames = false">展开全部帧</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stack-view {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.stack-view__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: var(--ts-space-sm) var(--ts-space-md);
  background: var(--ts-bg-elevated);
  border-bottom: var(--ts-border);
  font-size: var(--ts-font-size-xs);
  flex-wrap: wrap;
}

.stack-view__title {
  color: var(--ts-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
  margin-right: 4px;
}

.stack-view__spacer { flex: 1; }

.layer-chip {
  padding: 1px 8px;
  border-radius: var(--ts-radius-full);
  font-size: 10.5px;
  font-weight: 600;
  border: 1px solid transparent;
}
.layer-chip--business { color: #1d4ed8; background: #eff6ff; border-color: #bfdbfe; }
.layer-chip--framework { color: #6b7280; background: #f3f4f6; border-color: #e5e7eb; }
.layer-chip--jdk { color: #9ca3af; background: #fafafa; border-color: #eef0f3; }

.layer-hint {
  font-size: 10px;
  color: #b45309;
  background: #fffbeb;
  border: 1px dashed #fcd34d;
  border-radius: var(--ts-radius-full);
  padding: 0 6px;
  cursor: help;
}

.fold-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ts-text-secondary);
  cursor: pointer;
  user-select: none;
}

.stack-view__btn {
  padding: 2px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  color: var(--ts-text-secondary);
  font-size: var(--ts-font-size-xs);
  cursor: pointer;
  transition: all var(--ts-transition);
}
.stack-view__btn:hover { border-color: var(--ts-accent); color: var(--ts-accent); }

.stack-view__body {
  padding: var(--ts-space-sm) var(--ts-space-md);
  overflow: auto;
  flex: 1;
  min-height: 0;
}

/* ── Frames ── */
.frame {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: 1px 6px 1px 4px;
  font-size: 12px;
  line-height: 1.7;
  white-space: nowrap;
  border-left: 3px solid transparent;
  border-radius: 0 var(--ts-radius-xs) var(--ts-radius-xs) 0;
}
.frame:hover { background: var(--ts-bg-hover); }

.frame-idx {
  display: inline-block;
  min-width: 24px;
  text-align: right;
  color: var(--ts-text-muted);
  font-size: 10px;
  opacity: 0.6;
  flex-shrink: 0;
}
.frame-at { color: var(--ts-text-muted); }
.frame-pkg { color: var(--ts-text-muted); }
.frame-class { color: var(--ts-text-secondary); }
.frame-method { color: #2563eb; font-weight: 500; }
.frame-source { color: #16a34a; }
.frame-source--link {
  color: #16a34a;
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 2px;
}
.frame-source--link:hover { text-decoration-style: solid; color: #15803d; }

.frame--business {
  background: #f5f9ff;
  border-left-color: #2563eb;
}
.frame--business .frame-class { color: var(--ts-text-primary); font-weight: 600; }
.frame--business .frame-method { color: #1d4ed8; font-weight: 600; }

.frame--jdk .frame-pkg,
.frame--jdk .frame-class,
.frame--jdk .frame-method,
.frame--jdk .frame-source { color: var(--ts-text-muted); font-weight: 400; }

.frame-badge {
  font-family: var(--ts-font-ui);
  font-size: 9.5px;
  font-weight: 600;
  color: #1d4ed8;
  background: #dbeafe;
  border-radius: var(--ts-radius-full);
  padding: 0 6px;
  line-height: 1.6;
  flex-shrink: 0;
}

.frame-copy {
  margin-left: 6px;
  opacity: 0;
  font-family: var(--ts-font-ui);
  font-size: 10px;
  padding: 0 6px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-xs);
  background: var(--ts-bg-surface);
  color: var(--ts-text-muted);
  cursor: pointer;
  transition: opacity var(--ts-transition);
}
.frame:hover .frame-copy { opacity: 1; }

/* ── Fold row ── */
.fold-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 1px 0;
  padding: 1px 6px 1px 4px;
  font-size: 11px;
  font-family: var(--ts-font-ui);
  color: var(--ts-text-muted);
  background: repeating-linear-gradient(135deg, #fafbfc, #fafbfc 6px, #f3f4f6 6px, #f3f4f6 12px);
  border-left: 3px solid #e5e7eb;
  border-radius: 0 var(--ts-radius-xs) var(--ts-radius-xs) 0;
  cursor: pointer;
  white-space: nowrap;
}
.fold-row:hover { color: var(--ts-accent); border-left-color: var(--ts-accent); }
.fold-row__summary { color: var(--ts-text-faint); overflow: hidden; text-overflow: ellipsis; }
.fold-row:hover .fold-row__summary { color: var(--ts-text-muted); }
.fold-row__action { margin-left: auto; font-weight: 600; }

.fold-inline-toggle { padding-top: 4px; }

/* ── Lock actions ── */
.lock-action {
  font-size: 12px;
  padding: 2px 8px 2px 36px;
  margin: 1px 0;
  color: var(--ts-text-secondary);
  line-height: 1.7;
  cursor: pointer;
  border-radius: var(--ts-radius-sm);
  transition: background var(--ts-transition);
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}
.lock-action:hover { background: var(--ts-bg-hover); }
.lock-type { font-weight: 500; }
.lock-addr {
  color: var(--ts-accent);
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 2px;
}
.lock-goto { opacity: 0; font-size: 11px; color: var(--ts-accent); margin-left: 4px; }
.lock-action:hover .lock-goto { opacity: 1; }

.lock-action--held { border-left: 2px solid #16a34a; }
.lock-action--held .lock-type { color: #16a34a; }
.lock-action--blocked { border-left: 2px solid #dc2626; background: #fef2f2; }
.lock-action--blocked .lock-type { color: #dc2626; }
.lock-action--parking,
.lock-action--waiting { border-left: 2px solid #d97706; }
.lock-action--parking .lock-type,
.lock-action--waiting .lock-type { color: #d97706; }
.lock-action--contention.lock-action--held { background: #fefce8; border-left: 3px solid #dc2626; }

.lock-contention-badge {
  font-family: var(--ts-font-ui);
  font-size: 10px;
  font-weight: 600;
  padding: 1px 8px;
  margin-left: 6px;
  border-radius: var(--ts-radius-full);
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}
</style>
