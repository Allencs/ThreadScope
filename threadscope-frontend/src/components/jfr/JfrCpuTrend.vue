<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { JfrBucketRange, JfrCpuTimeline } from '@/api/threadscope'
import HintTip from '@/components/common/HintTip.vue'

export interface CpuTrendMethod {
  pkg: string
  name: string
  basis: 'self' | 'inclusive'
  timeline: number[]
}

const props = defineProps<{
  timeline: JfrCpuTimeline
  method: CpuTrendMethod | null
  /** default：未选择时默认展示排名第一的方法 */
  mode: 'default' | 'preview' | 'pinned'
  /** 选中的时间桶区间 [from, to) */
  range: JfrBucketRange | null
  /** 旧版报告的调用栈没有时间信息，不能框选 */
  rangeEnabled: boolean
}>()

const emit = defineEmits<{ select: [range: JfrBucketRange | null] }>()

const MODE_LABEL = { default: 'Top 1', preview: '预览', pinned: '已选中' } as const
const DEPLOYMENT_LABEL = { container: '容器', vm: '云主机 / 虚拟机', physical: '物理机' } as const

const userFill = `cpu-user-${useId()}`
const methodFill = `cpu-method-${useId()}`
const host = ref<HTMLDivElement | null>(null)
const width = ref(900)
const hover = ref<number | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!host.value) return
  width.value = host.value.clientWidth || 900
  observer = new ResizeObserver(entries => {
    width.value = Math.max(360, Math.floor(entries[0].contentRect.width))
  })
  observer.observe(host.value)
})
onBeforeUnmount(() => observer?.disconnect())

const PAD_L = 52
const PAD_R = 16
const Y0 = 18
const Y1 = 172
const AXIS_Y = 192
const HEIGHT = 202

const n = computed(() => Math.max(1, props.timeline.bucketCount))
const plotW = computed(() => Math.max(10, width.value - PAD_L - PAD_R))
const step = computed(() => plotW.value / n.value)
const hw = computed(() => props.timeline.hwThreads ?? null)
const bucketMs = computed(() => (props.timeline.endEpochMs - props.timeline.startEpochMs) / n.value)

/**
 * jdk.CPULoad 的分母是操作系统可见的全部逻辑核；容器里换算成占配额的比例，
 * 否则限流时曲线看起来只有十几个百分点。
 */
const container = computed(() => {
  const t = props.timeline
  return t.deployment === 'container' && t.limitCores != null && t.limitCores > 0 && hw.value != null
})
const unitCores = computed(() => (container.value ? props.timeline.limitCores! : hw.value))
const scale = computed(() => (container.value ? hw.value! / props.timeline.limitCores! : 1))

function view(v: number | null | undefined): number | null {
  return v == null ? null : v * 100 * scale.value
}

const user = computed(() => props.timeline.jvmUser.map(view))
const system = computed(() => props.timeline.jvmSystem.map(view))
const jvm = computed(() => user.value.map((u, i) => (u == null ? null : u + (system.value[i] ?? 0))))
/** 整机（容器里是节点）始终按占全部逻辑核的比例 */
const machine = computed(() => props.timeline.machineTotal.map(v => (v == null ? null : v * 100)))
const throttled = computed(() => props.timeline.throttled?.map(v => (v == null ? null : v * 100)) ?? null)

/** 方法估算：桶内 JVM CPU × 方法样本 / 桶内总样本 */
const methodSeries = computed<(number | null)[] | null>(() => {
  const m = props.method
  if (!m) return null
  return jvm.value.map((j, i) => {
    if (j == null) return null
    const total = props.timeline.samples[i] ?? 0
    return total > 0 ? (j * (m.timeline[i] ?? 0)) / total : 0
  })
})

/** 有选中区间时，统计只看区间内的桶 */
function stats(values: (number | null)[] | null): { avg: number; peak: number; peakAt: number } | null {
  if (!values) return null
  const r = props.range
  let sum = 0
  let count = 0
  let peak = -1
  let peakAt = 0
  values.forEach((v, i) => {
    if (v == null || (r && (i < r.from || i >= r.to))) return
    sum += v
    count++
    if (v > peak) {
      peak = v
      peakAt = i
    }
  })
  return count ? { avg: sum / count, peak, peakAt } : null
}

const jvmStats = computed(() => stats(jvm.value))
const machineStats = computed(() => stats(machine.value))
const methodStats = computed(() => stats(methodSeries.value))
const throttleStats = computed(() => stats(throttled.value))

const yMax = computed(() => {
  let hi = 0
  for (const v of jvm.value) if (v != null) hi = Math.max(hi, v)
  if (!container.value) for (const v of machine.value) if (v != null) hi = Math.max(hi, v)
  const nice = niceCeil(Math.max(1, hi * 1.12))
  // 用量过半时把配额线纳入视野，否则画面会被压扁
  return container.value && hi >= 50 ? Math.max(120, nice) : nice
})
const showLimit = computed(() => container.value && yMax.value >= 100)

function niceCeil(v: number): number {
  const exp = Math.pow(10, Math.floor(Math.log10(v)))
  for (const m of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * exp >= v) return m * exp
  return 10 * exp
}

function tickLabel(v: number): string {
  if (v === 0) return '0'
  return `${Number.isInteger(v) ? v : v.toFixed(v < 1 ? 2 : 1)}%`
}

const yTicks = computed(() => [0, yMax.value / 2, yMax.value])

function x(i: number): number {
  return PAD_L + (i + 0.5) * step.value
}

function y(v: number): number {
  return Y1 - (v / yMax.value) * (Y1 - Y0)
}

/** 连续有值的区段各画一块面积，没有数据的桶断开。 */
function area(top: (number | null)[], bottom?: (number | null)[]): string {
  let d = ''
  let seg: number[] = []
  const flush = () => {
    if (!seg.length) return
    const upper = seg.map((i, k) => `${k === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(top[i]!).toFixed(1)}`).join(' ')
    const lower = [...seg].reverse().map(i => `L${x(i).toFixed(1)},${y(bottom?.[i] ?? 0).toFixed(1)}`).join(' ')
    d += `${upper} ${lower} Z `
    seg = []
  }
  top.forEach((v, i) => {
    if (v == null) flush()
    else seg.push(i)
  })
  flush()
  return d
}

function line(values: (number | null)[]): string {
  let d = ''
  let pen = false
  values.forEach((v, i) => {
    if (v == null) {
      pen = false
      return
    }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)} `
    pen = true
  })
  return d
}

const userArea = computed(() => area(user.value))
const systemArea = computed(() => area(jvm.value, user.value))
const jvmLine = computed(() => line(jvm.value))
const machineLine = computed(() => (container.value ? '' : line(machine.value)))
const methodArea = computed(() => (methodSeries.value ? area(methodSeries.value) : ''))
const methodLine = computed(() => (methodSeries.value ? line(methodSeries.value) : ''))

/** 限流统计窗口约 30 秒，相邻同值的桶合并成一段 */
const throttleBands = computed(() => {
  const values = throttled.value
  if (!values) return []
  const bands: { x: number; w: number; ratio: number }[] = []
  let from = -1
  const close = (to: number) => {
    if (from < 0) return
    const ratio = values[from]! / 100
    bands.push({ x: PAD_L + from * step.value, w: (to - from) * step.value, ratio })
    from = -1
  }
  values.forEach((v, i) => {
    const on = v != null && v >= 0.5
    if (from >= 0 && (!on || v !== values[from])) close(i)
    if (on && from < 0) from = i
  })
  close(values.length)
  return bands
})

const timeTicks = computed(() => {
  const count = Math.min(7, n.value)
  const ticks: { x: number; label: string }[] = []
  for (let k = 0; k < count; k++) {
    const i = count === 1 ? 0 : Math.round((k * (n.value - 1)) / (count - 1))
    ticks.push({ x: x(i), label: clock(props.timeline.startEpochMs + i * bucketMs.value) })
  }
  return ticks
})

function clock(epoch: number): string {
  return new Date(epoch).toLocaleTimeString([], { hour12: false })
}

function pct(v: number): string {
  if (v > 0 && v < 0.01) return '<0.01%'
  return `${v < 10 ? v.toFixed(2) : v.toFixed(1)}%`
}

function coresOf(v: number, unit: number | null): string {
  if (unit == null) return ''
  const c = (v / 100) * unit
  return c > 0 && c < 0.01 ? '<0.01 核' : `${c.toFixed(2)} 核`
}

/** 图上的百分比换算成核 */
function cores(v: number): string {
  return coresOf(v, unitCores.value)
}

function trimCores(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(2).replace(/0+$/, '')
}

function bytes(v: number): string {
  const gb = v / 1024 ** 3
  return gb >= 1 ? `${trimCores(Math.round(gb * 10) / 10)} GB` : `${Math.round(v / 1024 ** 2)} MB`
}

const machineLabel = computed(() => (props.timeline.deployment === 'container' ? '节点' : '整机'))
const axisCaption = computed(() =>
  container.value ? `占容器配额（${trimCores(unitCores.value!)} 核）` : hw.value ? `占整机（${hw.value} 逻辑核）` : '占整机',
)

const env = computed(() => {
  const t = props.timeline
  const parts: string[] = []
  const c = t.container
  if (t.deployment === 'container' && c) {
    if (c.type) parts.push(c.type)
    if (c.quotaCores != null) parts.push(`CPU 配额 ${trimCores(c.quotaCores)} 核`)
    else if (c.effectiveCpuCount != null) parts.push(`可用 ${c.effectiveCpuCount} 核（无 quota）`)
    if (c.memoryLimit != null) parts.push(`内存上限 ${bytes(c.memoryLimit)}`)
    if (hw.value) parts.push(`节点 ${hw.value} 逻辑核${t.cores ? ` / ${t.cores} 物理核` : ''}${c.hostTotalMemory ? ` · ${bytes(c.hostTotalMemory)}` : ''}`)
  } else if (hw.value) {
    parts.push(`${hw.value} 逻辑核${t.cores ? ` / ${t.cores} 物理核` : ''}`)
  }
  if (t.virtualization && t.deployment !== 'physical') parts.push(t.virtualization)
  return parts.join(' · ')
})

function bucketAt(event: PointerEvent): number {
  const rect = (event.currentTarget as SVGRectElement).getBoundingClientRect()
  const i = Math.floor((event.clientX - rect.left) / step.value)
  return Math.max(0, Math.min(n.value - 1, i))
}

/** 拖动中的区间，松手后才通知父组件重新统计 */
const drag = ref<{ start: number; end: number } | null>(null)

function onDown(event: PointerEvent) {
  if (!props.rangeEnabled || event.button !== 0) return
  const i = bucketAt(event)
  drag.value = { start: i, end: i }
  ;(event.currentTarget as SVGRectElement).setPointerCapture(event.pointerId)
}

function onMove(event: PointerEvent) {
  const i = bucketAt(event)
  hover.value = i
  if (drag.value) drag.value.end = i
}

function onUp() {
  const d = drag.value
  if (!d) return
  drag.value = null
  if (d.start === d.end) return
  emit('select', { from: Math.min(d.start, d.end), to: Math.max(d.start, d.end) + 1 })
}

const selection = computed<JfrBucketRange | null>(() => {
  const d = drag.value
  if (d && d.start !== d.end) return { from: Math.min(d.start, d.end), to: Math.max(d.start, d.end) + 1 }
  return d ? null : props.range
})

function rangeLabel(r: JfrBucketRange): string {
  const start = props.timeline.startEpochMs + r.from * bucketMs.value
  const end = props.timeline.startEpochMs + r.to * bucketMs.value
  return `${clock(start)} – ${clock(end)} · ${span(end - start)}`
}

function span(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 90) return `${s} 秒`
  const m = Math.floor(s / 60)
  return m < 90 ? `${m} 分 ${s % 60} 秒` : `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

const selBox = computed(() => {
  const r = selection.value
  if (!r) return null
  const x0 = PAD_L + r.from * step.value
  const x1 = PAD_L + r.to * step.value
  return { x0, x1, mid: (x0 + x1) / 2, label: rangeLabel(r) }
})

const tip = computed(() => {
  const i = hover.value
  if (i == null) return null
  const start = props.timeline.startEpochMs + i * bucketMs.value
  const left = x(i)
  return {
    i,
    left,
    flip: left > width.value - 270,
    range: `${clock(start)} – ${clock(start + bucketMs.value)}`,
    machine: machine.value[i],
    user: user.value[i],
    system: system.value[i],
    jvm: jvm.value[i],
    throttled: throttled.value?.[i] ?? null,
    method: methodSeries.value?.[i] ?? null,
    methodSamples: props.method?.timeline[i] ?? 0,
    samples: props.timeline.samples[i] ?? 0,
  }
})

const shareOfJvm = computed(() => {
  if (!methodStats.value || !jvmStats.value || jvmStats.value.avg <= 0) return null
  return (methodStats.value.avg / jvmStats.value.avg) * 100
})
</script>

<template>
  <div class="cpu">
    <div class="cpu-head">
      <div class="cpu-title">
        <h3>
          CPU 使用率
          <HintTip heading="怎么读这张图" :width="440">
            <p v-if="container">
              百分比是占<b>容器 CPU 配额（{{ trimCores(unitCores!) }} 核）</b>的比例。jdk.CPULoad 的分母是节点全部 {{ hw }} 个逻辑核，这里已按配额换算；
              {{ machineLabel }}使用率包含同节点上的其它容器。
            </p>
            <p v-else>百分比是占整机 CPU 的比例。</p>
            <p v-if="container">
              <b>红色区域</b>来自 cgroup 的 CFS 限流统计（约 30 秒一次）：线程被限流时不运行，也不会被采样，所以该时段的采样数会偏少。
            </p>
            <p>
              <b>选中方法的曲线</b> = 每个时间段的 JVM CPU × 该方法在这段时间的采样占比，属于估算。JVM CPU 还包含 GC、JIT 等 VM 内部线程，而 CPU 采样只覆盖 Java 代码，所以估算值偏上限。
            </p>
            <p>悬停方法行可预览曲线，点击方法行固定选中。</p>
            <p v-if="rangeEnabled" class="hint-note">在图上<b>按住拖动</b>框选一段时间，下方热点方法和调用者树只统计这段时间的采样；按 Esc 或点击区间标签上的 × 取消。</p>
            <p v-else class="hint-note">这份报告由旧版解析生成，调用栈没有时间信息，重新上传后可框选时间区间。</p>
          </HintTip>
        </h3>
        <div v-if="timeline.deployment || env" class="env-line">
          <span v-if="timeline.deployment" class="env-tag" :class="`env-${timeline.deployment}`">{{ DEPLOYMENT_LABEL[timeline.deployment] }}</span>
          <span v-if="env" class="env">{{ env }}</span>
        </div>
        <p>
          <button v-if="range" type="button" class="range-chip" title="取消区间（Esc）" @click="emit('select', null)">
            区间 {{ rangeLabel(range) }}<span class="x">×</span>
          </button>
          <template v-if="jvmStats">
            {{ range ? '区间内 ' : '' }}JVM{{ container ? ' 占配额' : '' }} 平均 <b>{{ pct(jvmStats.avg) }}</b><template v-if="unitCores">（{{ cores(jvmStats.avg) }}）</template>，峰值
            <b>{{ pct(jvmStats.peak) }}</b><template v-if="container">（{{ cores(jvmStats.peak) }}）</template>
          </template>
          <template v-if="throttleStats && throttleStats.peak > 0">
            · <span class="throttle-text">被限流周期最高 <b>{{ pct(throttleStats.peak) }}</b></span>
          </template>
          <template v-if="machineStats">
            · {{ machineLabel }}平均 {{ pct(machineStats.avg) }}<template v-if="container">（{{ coresOf(machineStats.avg, hw) }}，含同节点其它进程）</template>
          </template>
        </p>
      </div>
      <div class="legend">
        <span><i class="sw user"></i>JVM user</span>
        <span><i class="sw system"></i>JVM system</span>
        <span v-if="!container"><i class="sw machine"></i>整机</span>
        <span v-if="showLimit"><i class="sw limit"></i>配额上限</span>
        <span v-if="throttleBands.length"><i class="sw throttle"></i>CPU 限流</span>
        <span v-if="method"><i class="sw method"></i>选中方法（估算）</span>
      </div>
    </div>

    <div v-if="method && methodStats" class="focus" :class="{ preview: mode === 'preview' }">
      <span class="focus-tag">{{ MODE_LABEL[mode] }}</span>
      <span class="focus-name" :title="method.pkg + method.name"><span class="pkg">{{ method.pkg }}</span><b>{{ method.name }}</b></span>
      <span class="focus-basis">{{ method.basis }}</span>
      <span class="focus-stat">平均 <b>{{ pct(methodStats.avg) }}</b><template v-if="unitCores"> ≈ {{ cores(methodStats.avg) }}</template></span>
      <span v-if="shareOfJvm != null" class="focus-stat share">占 JVM CPU <b>{{ shareOfJvm.toFixed(1) }}%</b></span>
      <span class="focus-stat peak">峰值 <b>{{ pct(methodStats.peak) }}</b><template v-if="unitCores"> ≈ {{ cores(methodStats.peak) }}</template> @ {{ clock(timeline.startEpochMs + methodStats.peakAt * bucketMs) }}</span>
    </div>

    <div ref="host" class="plot" @mouseleave="hover = null">
      <svg :width="width" :height="HEIGHT" :viewBox="`0 0 ${width} ${HEIGHT}`" role="img">
        <defs>
          <linearGradient :id="userFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="#2563eb" stop-opacity="0.30" />
            <stop offset="100%" stop-color="#2563eb" stop-opacity="0.06" />
          </linearGradient>
          <linearGradient :id="methodFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="#ea580c" stop-opacity="0.55" />
            <stop offset="100%" stop-color="#ea580c" stop-opacity="0.18" />
          </linearGradient>
        </defs>

        <text class="caption" :x="PAD_L" :y="8">{{ axisCaption }}</text>
        <text v-if="rangeEnabled && !selection" class="caption" :x="width - PAD_R" :y="8" text-anchor="end">按住拖动可框选时间区间</text>

        <g class="throttle">
          <rect v-for="(b, k) in throttleBands" :key="k" :x="b.x" :y="Y0" :width="b.w" :height="Y1 - Y0" :fill-opacity="0.04 + b.ratio * 0.22" />
        </g>

        <g class="grid">
          <line v-for="t in yTicks" :key="`g${t}`" :x1="PAD_L" :x2="width - PAD_R" :y1="y(t)" :y2="y(t)" />
          <text v-for="t in yTicks" :key="`t${t}`" :x="PAD_L - 8" :y="y(t) + 4" text-anchor="end">{{ tickLabel(t) }}</text>
        </g>

        <g v-if="showLimit" class="limit">
          <line :x1="PAD_L" :x2="width - PAD_R" :y1="y(100)" :y2="y(100)" />
          <text :x="width - PAD_R - 4" :y="y(100) - 5" text-anchor="end">配额 {{ trimCores(unitCores!) }} 核</text>
        </g>

        <path class="user-area" :d="userArea" :fill="`url(#${userFill})`" />
        <path class="system-area" :d="systemArea" />
        <path class="jvm-line" :d="jvmLine" />
        <path v-if="machineLine" class="machine-line" :d="machineLine" />
        <g v-if="methodSeries" :key="method?.pkg + (method?.name ?? '')" class="method-layer">
          <path class="method-area" :d="methodArea" :fill="`url(#${methodFill})`" />
          <path class="method-line" :d="methodLine" />
        </g>

        <g v-if="selBox" class="sel">
          <rect class="sel-dim" :x="PAD_L" :y="Y0 - 4" :width="Math.max(0, selBox.x0 - PAD_L)" :height="Y1 - Y0 + 4" />
          <rect class="sel-dim" :x="selBox.x1" :y="Y0 - 4" :width="Math.max(0, PAD_L + plotW - selBox.x1)" :height="Y1 - Y0 + 4" />
          <rect class="sel-band" :x="selBox.x0" :y="Y1" :width="selBox.x1 - selBox.x0" height="3" />
          <line :x1="selBox.x0" :x2="selBox.x0" :y1="Y0 - 4" :y2="Y1" />
          <line :x1="selBox.x1" :x2="selBox.x1" :y1="Y0 - 4" :y2="Y1" />
        </g>

        <g class="axis">
          <text v-for="(t, k) in timeTicks" :key="k" :x="t.x" :y="AXIS_Y" text-anchor="middle">{{ t.label }}</text>
        </g>

        <g v-if="tip && !drag" class="cursor">
          <line :x1="tip.left" :x2="tip.left" :y1="Y0 - 4" :y2="Y1" />
          <circle v-if="tip.jvm != null" :cx="tip.left" :cy="y(tip.jvm)" r="3.5" class="dot-jvm" />
          <circle v-if="tip.method != null && method" :cx="tip.left" :cy="y(tip.method)" r="3.5" class="dot-method" />
        </g>

        <rect
          class="hit"
          :class="{ selectable: rangeEnabled }"
          :x="PAD_L"
          :y="0"
          :width="plotW"
          :height="Y1 + 6"
          @pointerdown="onDown"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="drag = null"
        />
      </svg>

      <div v-if="selBox" class="sel-label" :class="{ live: !!drag }" :style="{ left: `${selBox.mid}px` }">{{ selBox.label }}</div>

      <div v-if="tip && !drag" class="tip" :class="{ flip: tip.flip }" :style="{ left: `${tip.left}px` }">
        <div class="tip-time">{{ tip.range }}</div>
        <template v-if="tip.jvm != null">
          <div class="tip-row"><i class="sw user"></i>JVM<b>{{ pct(tip.jvm) }}<small v-if="unitCores"> {{ cores(tip.jvm) }}</small></b></div>
          <div class="tip-row sub"><span>user {{ pct(tip.user ?? 0) }} · system {{ pct(tip.system ?? 0) }}<template v-if="container"> · 占配额</template></span></div>
          <div v-if="tip.throttled != null" class="tip-row" :class="{ warn: tip.throttled > 0 }">
            <i class="sw throttle"></i>被限流周期<b>{{ pct(tip.throttled) }}</b>
          </div>
          <div v-if="tip.throttled != null" class="tip-row sub"><span>cgroup 约 30 秒统计一次，按所在窗口展示</span></div>
          <div v-if="tip.machine != null" class="tip-row">
            <i class="sw machine"></i>{{ machineLabel }}<b>{{ pct(tip.machine) }}<small v-if="container && hw"> {{ coresOf(tip.machine, hw) }}</small></b>
          </div>
          <div v-if="method" class="tip-row"><i class="sw method"></i>选中方法<b>{{ pct(tip.method ?? 0) }}<small v-if="unitCores"> {{ cores(tip.method ?? 0) }}</small></b></div>
          <div class="tip-row sub"><span>CPU 采样 {{ tip.samples }}<template v-if="method">，其中该方法 {{ tip.methodSamples }}</template></span></div>
        </template>
        <div v-else class="tip-row sub"><span>这段时间没有 CPU 负载数据</span></div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.cpu { display: flex; flex-direction: column; gap: 10px; }
.cpu-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.cpu-title h3 { margin: 0; font-family: var(--ts-font-display); font-size: 14px; font-weight: 650; }
.env-line { display: flex; align-items: center; gap: 8px; margin-top: 5px; flex-wrap: wrap; }
.cpu-title p { margin: 4px 0 0; color: var(--ts-text-secondary); font-size: 12px; }
.cpu-title b { color: var(--ts-text-primary); font-variant-numeric: tabular-nums; }
.env-tag { flex: none; font-size: 11px; font-weight: 650; line-height: 18px; padding: 0 8px; border-radius: 999px; background: var(--ts-bg-inset); color: var(--ts-text-secondary); }
.env-tag.env-container { background: #e0f2fe; color: #0369a1; }
.env-tag.env-vm { background: #ede9fe; color: #6d28d9; }
.env-tag.env-physical { background: #dcfce7; color: #15803d; }
.env { font-size: 12px; color: var(--ts-text-secondary); }
.throttle-text, .throttle-text b { color: #b91c1c; }
.legend { display: flex; gap: 14px; flex-wrap: wrap; color: var(--ts-text-secondary); font-size: 12px; }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.focus {
  display: flex; align-items: center; gap: 12px; flex-wrap: nowrap; height: 34px; box-sizing: border-box; overflow: hidden;
  padding: 0 12px; border-radius: 8px; background: #fff7ed; border: 1px solid #fed7aa; font-size: 12.5px; white-space: nowrap;
  container-type: inline-size;
  transition: background var(--ts-transition), border-color var(--ts-transition);
}
.focus.preview { background: #fffbf5; border-style: dashed; }
.focus-tag { flex: none; font-size: 11px; font-weight: 650; color: #c2410c; background: #ffedd5; padding: 1px 7px; border-radius: 999px; }
.focus-name { flex: 0 1 auto; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; min-width: 60px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 520px; }
.focus-name .pkg { color: var(--ts-text-muted); }
.focus-name b { color: #9a3412; font-weight: 650; }
.focus-basis { flex: none; font-size: 11px; color: var(--ts-text-muted); border: var(--ts-border); border-radius: 4px; padding: 0 5px; }
.focus-stat { flex: none; color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; }
@container (max-width: 820px) { .focus-stat.peak { display: none; } }
@container (max-width: 600px) { .focus-stat.share, .focus-basis { display: none; } }
.focus-stat b { color: #9a3412; }
.plot { position: relative; width: 100%; user-select: none; }
svg { display: block; overflow: visible; }
.caption { fill: var(--ts-text-muted); font-size: 11px; }
.grid line { stroke: #eef0f4; stroke-width: 1; }
.grid text, .axis text { fill: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.throttle rect { fill: #dc2626; }
.limit line { stroke: #dc2626; stroke-width: 1.2; stroke-dasharray: 6 4; opacity: 0.75; }
.limit text { fill: #b91c1c; font-size: 11px; font-weight: 600; }
.system-area { fill: #818cf8; fill-opacity: 0.35; }
.jvm-line { fill: none; stroke: #2563eb; stroke-width: 1.6; stroke-linejoin: round; }
.machine-line { fill: none; stroke: #94a3b8; stroke-width: 1.4; stroke-dasharray: 5 4; }
.method-line { fill: none; stroke: #ea580c; stroke-width: 1.8; stroke-linejoin: round; }
.method-layer { animation: fade 360ms ease-out both; }
@keyframes fade { from { opacity: 0; } to { opacity: 1; } }
.cursor line { stroke: #94a3b8; stroke-width: 1; stroke-dasharray: 3 3; }
.dot-jvm { fill: #fff; stroke: #2563eb; stroke-width: 2; }
.dot-method { fill: #fff; stroke: #ea580c; stroke-width: 2; }
.hit { fill: transparent; cursor: crosshair; touch-action: none; }
.tip {
  position: absolute; top: 6px; transform: translateX(14px); min-width: 230px; padding: 10px 12px;
  background: rgba(255, 255, 255, 0.97); border: var(--ts-border); border-radius: 10px; box-shadow: var(--ts-shadow-lg);
  font-size: 12px; pointer-events: none; z-index: 2;
}
.tip.flip { transform: translateX(calc(-100% - 14px)); }
.tip-time { color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; margin-bottom: 6px; }
.tip-row { display: flex; align-items: center; gap: 6px; line-height: 20px; color: var(--ts-text-primary); }
.tip-row b { margin-left: auto; font-variant-numeric: tabular-nums; }
.tip-row small { color: var(--ts-text-muted); font-weight: 500; margin-left: 4px; }
.tip-row.warn, .tip-row.warn b { color: #b91c1c; }
.tip-row.sub { color: var(--ts-text-muted); font-size: 11.5px; line-height: 17px; padding-left: 16px; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.sw.user { background: #2563eb; }
.sw.system { background: #a5b4fc; }
.sw.machine { background: repeating-linear-gradient(90deg, #94a3b8 0 4px, transparent 4px 6px); height: 3px; border-radius: 0; }
.sw.limit { background: repeating-linear-gradient(90deg, #dc2626 0 4px, transparent 4px 6px); height: 3px; border-radius: 0; }
.sw.throttle { background: rgba(220, 38, 38, 0.35); }
.sw.method { background: #ea580c; }
.cpu-title h3 :deep(.hint) { margin-left: 6px; }
.range-chip {
  display: inline-flex; align-items: center; gap: 6px; margin-right: 8px; padding: 1px 6px 1px 9px;
  border: 1px solid #bfdbfe; border-radius: 999px; background: #eff6ff; color: #1d4ed8;
  font: inherit; font-size: 11.5px; font-weight: 600; font-variant-numeric: tabular-nums; cursor: pointer;
  transition: background var(--ts-transition);
}
.range-chip:hover { background: #dbeafe; }
.range-chip .x { display: inline-grid; place-items: center; width: 15px; height: 15px; border-radius: 50%; background: #bfdbfe; font-size: 12px; line-height: 1; }
.sel-dim { fill: #f8fafc; fill-opacity: 0.72; pointer-events: none; }
.sel-band { fill: #2563eb; pointer-events: none; }
.sel line { stroke: #2563eb; stroke-width: 1.2; pointer-events: none; }
.sel-label {
  position: absolute; top: -2px; transform: translateX(-50%); padding: 0 8px; border-radius: 999px;
  background: #2563eb; color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; white-space: nowrap;
  font-variant-numeric: tabular-nums; pointer-events: none; box-shadow: 0 2px 6px rgba(37, 99, 235, 0.3);
}
.sel-label.live { background: #1e40af; }</style>
