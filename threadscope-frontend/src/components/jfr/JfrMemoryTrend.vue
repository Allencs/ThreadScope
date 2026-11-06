<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { JfrAllocationSource, JfrBucketRange, JfrMemoryOverview } from '@/api/threadscope'
import HintTip from '@/components/common/HintTip.vue'
import { formatBytes, formatNanos, formatRate } from '@/utils/bytes'

export interface MemoryTrendMethod {
  pkg: string
  name: string
  basisLabel: string
  /** 每个时间桶的估算字节数 */
  timeline: number[]
}

const props = defineProps<{
  overview: JfrMemoryOverview
  source: JfrAllocationSource | null
  method: MemoryTrendMethod | null
  mode: 'default' | 'preview' | 'pinned'
  range: JfrBucketRange | null
  rangeEnabled: boolean
}>()

const emit = defineEmits<{ select: [range: JfrBucketRange | null] }>()

const MODE_LABEL = { default: 'Top 1', preview: '预览', pinned: '已选中' } as const

const methodFill = `mem-method-${useId()}`
const heapFill = `mem-heap-${useId()}`
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

const PAD_L = 64
const PAD_R = 16
const A0 = 22
const A1 = 120
const H0 = 156
const H1 = 262
const AXIS_Y = 282
const HEIGHT = 292

const n = computed(() => Math.max(1, props.overview.bucketCount))
const plotW = computed(() => Math.max(10, width.value - PAD_L - PAD_R))
const step = computed(() => plotW.value / n.value)
const bucketMs = computed(() => (props.overview.endEpochMs - props.overview.startEpochMs) / n.value)
const bucketSec = computed(() => Math.max(0.001, bucketMs.value / 1000))

function bucketOf(epoch: number): number {
  const i = Math.floor((epoch - props.overview.startEpochMs) / Math.max(1, bucketMs.value))
  return Math.max(0, Math.min(n.value - 1, i))
}

/** 分配事件只在 cover 范围内出现，范围外画成空白而不是 0 */
const cover = computed(() => {
  const s = props.source
  if (!s || s.coverStartEpochMs == null || s.coverEndEpochMs == null) return null
  return { from: bucketOf(s.coverStartEpochMs), to: bucketOf(s.coverEndEpochMs) }
})

function covered(i: number): boolean {
  const c = cover.value
  return !!c && i >= c.from && i <= c.to
}

const estimated = computed<(number | null)[]>(() =>
  Array.from({ length: n.value }, (_, i) => (covered(i) ? (props.source?.bucketBytes[i] ?? 0) / bucketSec.value : null)),
)

/** 采集间隔比时间桶粗很多时（default 模板按 chunk 采集），逐桶的实际值是锯齿，不画曲线 */
const measuredFine = computed(() => {
  const o = props.overview
  return !!o.measuredBytes && o.measuredIntervalMs != null && o.measuredIntervalMs <= bucketMs.value * 3
})
const measured = computed<(number | null)[] | null>(() =>
  measuredFine.value ? props.overview.measuredBytes!.map(v => v / bucketSec.value) : null,
)

const methodSeries = computed<(number | null)[] | null>(() => {
  const m = props.method
  if (!m) return null
  return Array.from({ length: n.value }, (_, i) => (covered(i) ? (m.timeline[i] ?? 0) / bucketSec.value : null))
})

const rateMax = computed(() => {
  let hi = 0
  for (const v of estimated.value) if (v != null) hi = Math.max(hi, v)
  for (const v of measured.value ?? []) if (v != null) hi = Math.max(hi, v)
  return niceCeil(Math.max(1024 * 1024, hi * 1.1))
})

const heapMax = computed(() => {
  let hi = props.overview.heapMax ?? 0
  for (const list of [props.overview.heapPeak, props.overview.rss, props.overview.container]) {
    for (const v of list) if (v != null) hi = Math.max(hi, v)
  }
  return niceCeil(Math.max(1024 * 1024, hi * 1.08))
})

function niceCeil(v: number): number {
  const exp = Math.pow(10, Math.floor(Math.log10(v)))
  for (const m of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) if (m * exp >= v) return m * exp
  return 10 * exp
}

function x(i: number): number {
  return PAD_L + (i + 0.5) * step.value
}

function ya(v: number): number {
  return A1 - (v / rateMax.value) * (A1 - A0)
}

function yh(v: number): number {
  return H1 - (v / heapMax.value) * (H1 - H0)
}

function line(values: (number | null)[], y: (v: number) => number): string {
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

function area(values: (number | null)[], y: (v: number) => number, base: number): string {
  let d = ''
  let seg: number[] = []
  const flush = () => {
    if (!seg.length) return
    const top = seg.map((i, k) => `${k === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(values[i]!).toFixed(1)}`).join(' ')
    d += `${top} L${x(seg[seg.length - 1]).toFixed(1)},${base} L${x(seg[0]).toFixed(1)},${base} Z `
    seg = []
  }
  values.forEach((v, i) => {
    if (v == null) flush()
    else seg.push(i)
  })
  flush()
  return d
}

const bars = computed(() => {
  const w = Math.max(1, step.value - (step.value > 4 ? 1 : 0))
  return estimated.value.flatMap((v, i) => (v == null || v <= 0 ? [] : [{ x: PAD_L + i * step.value, y: ya(v), w, h: A1 - ya(v) }]))
})
const measuredLine = computed(() => (measured.value ? line(measured.value, ya) : ''))
const methodArea = computed(() => (methodSeries.value ? area(methodSeries.value, ya, A1) : ''))
const methodLine = computed(() => (methodSeries.value ? line(methodSeries.value, ya) : ''))

const heapArea = computed(() => area(props.overview.heapPeak, yh, H1))
const heapLine = computed(() => line(props.overview.heapPeak, yh))
const rssLine = computed(() => line(props.overview.rss, yh))
const containerLine = computed(() => line(props.overview.container, yh))
const floorDots = computed(() =>
  props.overview.heapFloor.flatMap((v, i) => (v == null ? [] : [{ x: x(i), y: yh(v) }])),
)
const hasRss = computed(() => props.overview.rss.some(v => v != null))
const hasContainer = computed(() => props.overview.container.some(v => v != null))

/** 分配停顿：按桶内累计停顿时间着色 */
const stallBands = computed(() => {
  const nanos = props.overview.stallNanosBuckets
  const worst = Math.max(1, ...nanos.map(v => v ?? 0))
  return nanos.flatMap((v, i) => (v ? [{ x: PAD_L + i * step.value, w: step.value, ratio: v / worst }] : []))
})

const gcTicks = computed(() =>
  props.overview.gcCount.flatMap((v, i) => (v ? [{ x: x(i), count: v }] : [])),
)

const rateTicks = computed(() => [0, rateMax.value / 2, rateMax.value])
const heapTicks = computed(() => [0, heapMax.value / 2, heapMax.value])

const timeTicks = computed(() => {
  const count = Math.min(7, n.value)
  const ticks: { x: number; label: string }[] = []
  for (let k = 0; k < count; k++) {
    const i = count === 1 ? 0 : Math.round((k * (n.value - 1)) / (count - 1))
    ticks.push({ x: x(i), label: clock(props.overview.startEpochMs + i * bucketMs.value) })
  }
  return ticks
})

function clock(epoch: number): string {
  return new Date(epoch).toLocaleTimeString([], { hour12: false })
}

function span(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 90) return `${s} 秒`
  const m = Math.floor(s / 60)
  return m < 90 ? `${m} 分 ${s % 60} 秒` : `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

function compactBytes(v: number): string {
  return v === 0 ? '0' : formatBytes(v)
}

/** 统计只看选中区间内、且有分配事件的桶 */
function average(values: (number | null)[] | null): number | null {
  if (!values) return null
  const r = props.range
  let sum = 0
  let count = 0
  values.forEach((v, i) => {
    if (v == null || (r && (i < r.from || i >= r.to))) return
    sum += v
    count++
  })
  return count ? sum / count : null
}

const estimatedAvg = computed(() => average(estimated.value))
const measuredAvg = computed(() => {
  const o = props.overview
  if (!o.measuredBytes) return null
  const r = props.range
  let sum = 0
  const from = r?.from ?? 0
  const to = r?.to ?? n.value
  for (let i = from; i < to; i++) sum += o.measuredBytes[i] ?? 0
  return sum / Math.max(0.001, ((to - from) * bucketMs.value) / 1000)
})
const methodAvg = computed(() => average(methodSeries.value))
const methodShare = computed(() => {
  if (methodAvg.value == null || !estimatedAvg.value) return null
  return (methodAvg.value / estimatedAvg.value) * 100
})

function bucketAt(event: PointerEvent): number {
  const rect = (event.currentTarget as SVGRectElement).getBoundingClientRect()
  const i = Math.floor((event.clientX - rect.left) / step.value)
  return Math.max(0, Math.min(n.value - 1, i))
}

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
  const start = props.overview.startEpochMs + r.from * bucketMs.value
  const end = props.overview.startEpochMs + r.to * bucketMs.value
  return `${clock(start)} – ${clock(end)} · ${span(end - start)}`
}

const selBox = computed(() => {
  const r = selection.value
  if (!r) return null
  const x0 = PAD_L + r.from * step.value
  const x1 = PAD_L + r.to * step.value
  return { x0, x1, mid: (x0 + x1) / 2, label: rangeLabel(r) }
})

const coverBox = computed(() => {
  const c = cover.value
  if (!c || (c.from === 0 && c.to === n.value - 1)) return null
  return { x0: PAD_L + c.from * step.value, x1: PAD_L + (c.to + 1) * step.value }
})

const coverGaps = computed(() => {
  const box = coverBox.value
  if (!box) return []
  return [
    { x: PAD_L, w: box.x0 - PAD_L },
    { x: box.x1, w: PAD_L + plotW.value - box.x1 },
  ].filter(gap => gap.w > 0)
})

const tip = computed(() => {
  const i = hover.value
  if (i == null) return null
  const o = props.overview
  const start = o.startEpochMs + i * bucketMs.value
  const left = x(i)
  return {
    left,
    flip: left > width.value - 280,
    range: `${clock(start)} – ${clock(start + bucketMs.value)}`,
    estimated: estimated.value[i],
    measured: o.measuredBytes ? o.measuredBytes[i] / bucketSec.value : null,
    method: methodSeries.value?.[i] ?? null,
    heapPeak: o.heapPeak[i],
    heapFloor: o.heapFloor[i],
    rss: o.rss[i],
    gc: o.gcCount[i],
    gcPause: o.gcPauseNanos[i],
    stalls: o.stallBuckets[i],
    stallNanos: o.stallNanosBuckets[i],
  }
})
</script>

<template>
  <div class="mem">
    <div class="mem-head">
      <div class="mem-title">
        <h3>
          分配速率与堆内存
          <HintTip heading="怎么读这张图" :width="460">
            <p><b>上半部分</b>是分配速率。柱子是按分配事件估算的速率{{ source?.source === 'tlab' ? '（TLAB 事件，按 TLAB 大小计，与 JMC「估计 TLAB 分配」同口径）' : '（ObjectAllocationSample 的 weight 之和）' }}；
              <template v-if="measured">深色曲线是 ThreadAllocationStatistics 给出的<b>实际</b>分配速率。</template>
              <template v-else-if="overview.measuredBytes">实际分配量采集间隔较粗，只在上方给出平均值。</template>
            </p>
            <p>选中方法的橙色面积 = 该方法每段时间的估算分配量，和柱子同一口径。</p>
            <p><b>下半部分</b>是堆占用：蓝色是每段时间内 GC 前后的最高占用，深色圆点是 <b>GC 后</b>的占用（存活对象下限，持续抬升说明有对象在累积）；红色虚线是堆上限。
              <template v-if="hasRss">灰色虚线是进程 RSS（含堆外内存）。</template>
            </p>
            <p>底部小刻度是 GC，<span class="stall-text">红色竖带</span>是 ZGC 分配停顿（线程等 GC 腾空间），颜色越深停顿越久。</p>
            <p v-if="rangeEnabled" class="hint-note">在图上<b>按住拖动</b>框选时间段，下方分配热点只统计这段时间；按 Esc 取消。</p>
          </HintTip>
        </h3>
        <p>
          <button v-if="range" type="button" class="range-chip" title="取消区间（Esc）" @click="emit('select', null)">
            区间 {{ rangeLabel(range) }}<span class="x">×</span>
          </button>
          <template v-if="measuredAvg != null">{{ range ? '区间内' : '' }}实际分配 <b>{{ formatRate(measuredAvg) }}</b></template>
          <template v-if="estimatedAvg != null"><template v-if="measuredAvg != null"> · </template>事件估算 <b>{{ formatRate(estimatedAvg) }}</b></template>
          <template v-if="overview.heapMax"> · 堆上限 <b>{{ formatBytes(overview.heapMax) }}</b></template>
          <template v-if="overview.stallCount"> · <span class="stall-text">分配停顿 <b>{{ overview.stallCount }}</b> 次</span></template>
        </p>
      </div>
      <div class="legend">
        <span><i class="sw est"></i>估算分配</span>
        <span v-if="measured"><i class="sw measured"></i>实际分配</span>
        <span v-if="method"><i class="sw method"></i>选中方法</span>
        <span><i class="sw heap"></i>堆占用</span>
        <span><i class="sw floor"></i>GC 后</span>
        <span v-if="overview.heapMax"><i class="sw limit"></i>堆上限</span>
        <span v-if="hasRss"><i class="sw rss"></i>RSS</span>
        <span v-if="stallBands.length"><i class="sw stall"></i>分配停顿</span>
      </div>
    </div>

    <div v-if="method && methodAvg != null" class="focus" :class="{ preview: mode === 'preview' }">
      <span class="focus-tag">{{ MODE_LABEL[mode] }}</span>
      <span class="focus-name" :title="method.pkg + method.name"><span class="pkg">{{ method.pkg }}</span><b>{{ method.name }}</b></span>
      <span class="focus-basis">{{ method.basisLabel }}</span>
      <span class="focus-stat">平均 <b>{{ formatRate(methodAvg) }}</b></span>
      <span v-if="methodShare != null" class="focus-stat share">占估算分配 <b>{{ methodShare.toFixed(1) }}%</b></span>
    </div>

    <div ref="host" class="plot" @mouseleave="hover = null">
      <svg :width="width" :height="HEIGHT" :viewBox="`0 0 ${width} ${HEIGHT}`" role="img">
        <defs>
          <linearGradient :id="methodFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="#ea580c" stop-opacity="0.6" />
            <stop offset="100%" stop-color="#ea580c" stop-opacity="0.2" />
          </linearGradient>
          <linearGradient :id="heapFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="#0891b2" stop-opacity="0.28" />
            <stop offset="100%" stop-color="#0891b2" stop-opacity="0.04" />
          </linearGradient>
        </defs>

        <text class="caption" :x="PAD_L" :y="10">分配速率（每秒）</text>
        <text v-if="rangeEnabled && !selection" class="caption" :x="width - PAD_R" :y="10" text-anchor="end">按住拖动可框选时间区间</text>
        <text class="caption" :x="PAD_L" :y="H0 - 10">堆内存</text>

        <g v-if="coverBox" class="cover">
          <template v-for="(gap, k) in coverGaps" :key="k">
            <rect :x="gap.x" :y="A0" :width="gap.w" :height="A1 - A0" />
            <text v-if="gap.w > 110" :x="gap.x + gap.w / 2" :y="(A0 + A1) / 2 + 4" text-anchor="middle">
              没有{{ source?.source === 'tlab' ? ' TLAB ' : '分配' }}事件
            </text>
          </template>
        </g>

        <g class="stall">
          <rect v-for="(b, k) in stallBands" :key="k" :x="b.x" :y="H0" :width="b.w" :height="H1 - H0" :fill-opacity="0.12 + b.ratio * 0.45" />
        </g>

        <g class="grid">
          <line v-for="t in rateTicks" :key="`ga${t}`" :x1="PAD_L" :x2="width - PAD_R" :y1="ya(t)" :y2="ya(t)" />
          <text v-for="t in rateTicks" :key="`ta${t}`" :x="PAD_L - 8" :y="ya(t) + 4" text-anchor="end">{{ compactBytes(t) }}</text>
          <line v-for="t in heapTicks" :key="`gh${t}`" :x1="PAD_L" :x2="width - PAD_R" :y1="yh(t)" :y2="yh(t)" />
          <text v-for="t in heapTicks" :key="`th${t}`" :x="PAD_L - 8" :y="yh(t) + 4" text-anchor="end">{{ compactBytes(t) }}</text>
        </g>

        <g class="bars">
          <rect v-for="(b, k) in bars" :key="k" :x="b.x" :y="b.y" :width="b.w" :height="b.h" />
        </g>
        <path v-if="measuredLine" class="measured-line" :d="measuredLine" />
        <g v-if="methodSeries" :key="method?.pkg + (method?.name ?? '')" class="method-layer">
          <path :d="methodArea" :fill="`url(#${methodFill})`" />
          <path class="method-line" :d="methodLine" />
        </g>

        <path class="heap-area" :d="heapArea" :fill="`url(#${heapFill})`" />
        <path class="heap-line" :d="heapLine" />
        <path v-if="hasContainer" class="container-line" :d="containerLine" />
        <path v-if="hasRss" class="rss-line" :d="rssLine" />
        <circle v-for="(d, k) in floorDots" :key="`f${k}`" class="floor" :cx="d.x" :cy="d.y" r="2.2" />
        <g v-if="overview.heapMax" class="limit">
          <line :x1="PAD_L" :x2="width - PAD_R" :y1="yh(overview.heapMax)" :y2="yh(overview.heapMax)" />
          <text :x="width - PAD_R - 4" :y="yh(overview.heapMax) - 5" text-anchor="end">堆上限 {{ formatBytes(overview.heapMax) }}</text>
        </g>
        <g class="gc">
          <line v-for="(t, k) in gcTicks" :key="k" :x1="t.x" :x2="t.x" :y1="H1" :y2="H1 + 4 + Math.min(4, t.count)" />
        </g>

        <g v-if="selBox" class="sel">
          <rect class="sel-dim" :x="PAD_L" :y="A0 - 4" :width="Math.max(0, selBox.x0 - PAD_L)" :height="H1 - A0 + 4" />
          <rect class="sel-dim" :x="selBox.x1" :y="A0 - 4" :width="Math.max(0, PAD_L + plotW - selBox.x1)" :height="H1 - A0 + 4" />
          <line :x1="selBox.x0" :x2="selBox.x0" :y1="A0 - 4" :y2="H1" />
          <line :x1="selBox.x1" :x2="selBox.x1" :y1="A0 - 4" :y2="H1" />
        </g>

        <g class="axis">
          <text v-for="(t, k) in timeTicks" :key="k" :x="t.x" :y="AXIS_Y" text-anchor="middle">{{ t.label }}</text>
        </g>

        <g v-if="tip && !drag" class="cursor">
          <line :x1="tip.left" :x2="tip.left" :y1="A0 - 4" :y2="H1" />
        </g>

        <rect
          class="hit"
          :class="{ selectable: rangeEnabled }"
          :x="PAD_L"
          :y="0"
          :width="plotW"
          :height="H1 + 8"
          @pointerdown="onDown"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="drag = null"
        />
      </svg>

      <div v-if="selBox" class="sel-label" :class="{ live: !!drag }" :style="{ left: `${selBox.mid}px` }">{{ selBox.label }}</div>

      <div v-if="tip && !drag" class="tip" :class="{ flip: tip.flip }" :style="{ left: `${tip.left}px` }">
        <div class="tip-time">{{ tip.range }}</div>
        <div class="tip-row"><i class="sw est"></i>估算分配<b>{{ tip.estimated == null ? '无事件' : formatRate(tip.estimated) }}</b></div>
        <div v-if="tip.measured != null" class="tip-row"><i class="sw measured"></i>实际分配<b>{{ formatRate(tip.measured) }}</b></div>
        <div v-if="method" class="tip-row"><i class="sw method"></i>选中方法<b>{{ tip.method == null ? '—' : formatRate(tip.method) }}</b></div>
        <div class="tip-row"><i class="sw heap"></i>堆占用峰值<b>{{ formatBytes(tip.heapPeak) }}</b></div>
        <div v-if="tip.heapFloor != null" class="tip-row"><i class="sw floor"></i>GC 后<b>{{ formatBytes(tip.heapFloor) }}</b></div>
        <div v-if="tip.rss != null" class="tip-row"><i class="sw rss"></i>RSS<b>{{ formatBytes(tip.rss) }}</b></div>
        <div v-if="tip.gc" class="tip-row sub"><span>GC {{ tip.gc }} 次，停顿 {{ formatNanos(tip.gcPause) }}</span></div>
        <div v-if="tip.stalls" class="tip-row warn"><i class="sw stall"></i>分配停顿 {{ tip.stalls }} 次<b>{{ formatNanos(tip.stallNanos) }}</b></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mem { display: flex; flex-direction: column; gap: 10px; }
.mem-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.mem-title h3 { margin: 0; font-family: var(--ts-font-display); font-size: 14px; font-weight: 650; }
.mem-title h3 :deep(.hint) { margin-left: 6px; }
.mem-title p { margin: 4px 0 0; color: var(--ts-text-secondary); font-size: 12px; }
.mem-title b { color: var(--ts-text-primary); font-variant-numeric: tabular-nums; }
.stall-text, .stall-text b { color: #b91c1c; }
.legend { display: flex; gap: 14px; flex-wrap: wrap; color: var(--ts-text-secondary); font-size: 12px; }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.focus {
  display: flex; align-items: center; gap: 12px; height: 34px; box-sizing: border-box; overflow: hidden;
  padding: 0 12px; border-radius: 8px; background: #fff7ed; border: 1px solid #fed7aa; font-size: 12.5px; white-space: nowrap;
}
.focus.preview { background: #fffbf5; border-style: dashed; }
.focus-tag { flex: none; font-size: 11px; font-weight: 650; color: #c2410c; background: #ffedd5; padding: 1px 7px; border-radius: 999px; }
.focus-name { flex: 0 1 auto; font-family: var(--ts-font-display); font-size: 12px; min-width: 60px; overflow: hidden; text-overflow: ellipsis; max-width: 560px; }
.focus-name .pkg { color: var(--ts-text-muted); }
.focus-name b { color: #9a3412; font-weight: 650; }
.focus-basis { flex: none; font-size: 11px; color: var(--ts-text-muted); border: var(--ts-border); border-radius: 4px; padding: 0 5px; }
.focus-stat { flex: none; color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; }
.focus-stat b { color: #9a3412; }
.plot { position: relative; width: 100%; user-select: none; }
svg { display: block; overflow: visible; }
.caption { fill: var(--ts-text-muted); font-size: 11px; }
.grid line { stroke: #eef0f4; stroke-width: 1; }
.grid text, .axis text { fill: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.cover rect { fill: #f1f5f9; fill-opacity: 0.8; }
.cover text { fill: var(--ts-text-muted); font-size: 11px; }
.bars rect { fill: #93c5fd; }
.measured-line { fill: none; stroke: #1e3a8a; stroke-width: 1.6; stroke-linejoin: round; }
.method-line { fill: none; stroke: #ea580c; stroke-width: 1.8; stroke-linejoin: round; }
.method-layer { animation: fade 360ms ease-out both; }
@keyframes fade { from { opacity: 0; } to { opacity: 1; } }
.heap-line { fill: none; stroke: #0891b2; stroke-width: 1.6; stroke-linejoin: round; }
.rss-line { fill: none; stroke: #64748b; stroke-width: 1.3; stroke-dasharray: 5 4; }
.container-line { fill: none; stroke: #a855f7; stroke-width: 1.2; stroke-dasharray: 2 3; }
.floor { fill: #0f172a; }
.limit line { stroke: #dc2626; stroke-width: 1.2; stroke-dasharray: 6 4; opacity: 0.75; }
.limit text { fill: #b91c1c; font-size: 11px; font-weight: 600; }
.stall rect { fill: #dc2626; }
.gc line { stroke: #64748b; stroke-width: 1; }
.cursor line { stroke: #94a3b8; stroke-width: 1; stroke-dasharray: 3 3; }
.hit { fill: transparent; touch-action: none; }
.hit.selectable { cursor: crosshair; }
.tip {
  position: absolute; top: 6px; transform: translateX(14px); min-width: 240px; padding: 10px 12px;
  background: rgba(255, 255, 255, 0.97); border: var(--ts-border); border-radius: 10px; box-shadow: var(--ts-shadow-lg);
  font-size: 12px; pointer-events: none; z-index: 2;
}
.tip.flip { transform: translateX(calc(-100% - 14px)); }
.tip-time { color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; margin-bottom: 6px; }
.tip-row { display: flex; align-items: center; gap: 6px; line-height: 20px; color: var(--ts-text-primary); }
.tip-row b { margin-left: auto; padding-left: 12px; font-variant-numeric: tabular-nums; }
.tip-row.warn, .tip-row.warn b { color: #b91c1c; }
.tip-row.sub { color: var(--ts-text-muted); font-size: 11.5px; line-height: 17px; padding-left: 16px; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.sw.est { background: #93c5fd; }
.sw.measured { background: #1e3a8a; height: 3px; border-radius: 0; }
.sw.method { background: #ea580c; }
.sw.heap { background: #0891b2; }
.sw.floor { background: #0f172a; border-radius: 50%; width: 7px; height: 7px; }
.sw.limit { background: repeating-linear-gradient(90deg, #dc2626 0 4px, transparent 4px 6px); height: 3px; border-radius: 0; }
.sw.rss { background: repeating-linear-gradient(90deg, #64748b 0 4px, transparent 4px 6px); height: 3px; border-radius: 0; }
.sw.stall { background: rgba(220, 38, 38, 0.45); }
.range-chip {
  display: inline-flex; align-items: center; gap: 6px; margin-right: 8px; padding: 1px 6px 1px 9px;
  border: 1px solid #bfdbfe; border-radius: 999px; background: #eff6ff; color: #1d4ed8;
  font: inherit; font-size: 11.5px; font-weight: 600; font-variant-numeric: tabular-nums; cursor: pointer;
}
.range-chip:hover { background: #dbeafe; }
.range-chip .x { display: inline-grid; place-items: center; width: 15px; height: 15px; border-radius: 50%; background: #bfdbfe; font-size: 12px; line-height: 1; }
.sel-dim { fill: #f8fafc; fill-opacity: 0.72; pointer-events: none; }
.sel line { stroke: #2563eb; stroke-width: 1.2; pointer-events: none; }
.sel-label {
  position: absolute; top: -2px; transform: translateX(-50%); padding: 0 8px; border-radius: 999px;
  background: #2563eb; color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; white-space: nowrap;
  font-variant-numeric: tabular-nums; pointer-events: none; box-shadow: 0 2px 6px rgba(37, 99, 235, 0.3);
}
.sel-label.live { background: #1e40af; }
</style>
