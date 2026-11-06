<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { JfrLifecycleSeries } from '@/api/threadscope'

const props = defineProps<{
  series: JfrLifecycleSeries
  jvmActive?: (number | null)[] | null
  windowStart: number
  bucketMillis: number
  animateKey: string
}>()

const fillId = `alive-fill-${useId()}`
const host = ref<HTMLDivElement | null>(null)
const width = ref(800)
const hover = ref<number | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!host.value) return
  width.value = host.value.clientWidth || 800
  observer = new ResizeObserver(entries => {
    width.value = Math.max(320, Math.floor(entries[0].contentRect.width))
  })
  observer.observe(host.value)
})
onBeforeUnmount(() => observer?.disconnect())

const PAD_L = 56
const PAD_R = 18
const TOP_Y0 = 14
const TOP_Y1 = 150
const BAR_Y0 = 178
const BAR_Y1 = 262
const AXIS_Y = 284
const HEIGHT = 296

const n = computed(() => Math.max(1, props.series.alive.length))
const plotW = computed(() => Math.max(10, width.value - PAD_L - PAD_R))
const step = computed(() => plotW.value / n.value)
const barW = computed(() => Math.max(2, Math.min(14, step.value * 0.62)))
const zeroY = (BAR_Y0 + BAR_Y1) / 2

function x(i: number): number {
  return PAD_L + (i + 0.5) * step.value
}

const jvm = computed(() => (props.jvmActive ?? []).slice(0, n.value))
const hasJvm = computed(() => jvm.value.some(v => v != null))

const domain = computed(() => {
  const values = [...props.series.alive]
  for (const v of jvm.value) if (v != null) values.push(v)
  let lo = Math.min(...values)
  let hi = Math.max(...values)
  if (!Number.isFinite(lo)) { lo = 0; hi = 1 }
  const span = hi - lo
  const pad = Math.max(2, Math.ceil(span * 0.18))
  lo = Math.max(0, lo - pad)
  hi = hi + pad
  return { lo, hi }
})

function y(v: number): number {
  const { lo, hi } = domain.value
  return TOP_Y1 - ((v - lo) / Math.max(1, hi - lo)) * (TOP_Y1 - TOP_Y0)
}

const yTicks = computed(() => {
  const { lo, hi } = domain.value
  const raw = [lo, (lo + hi) / 2, hi].map(v => Math.round(v))
  return [...new Set(raw)]
})

const alivePath = computed(() =>
  props.series.alive.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
)
const aliveArea = computed(() => {
  const a = props.series.alive
  if (a.length === 0) return ''
  return `${alivePath.value} L${x(a.length - 1).toFixed(1)},${TOP_Y1} L${x(0).toFixed(1)},${TOP_Y1} Z`
})
const jvmPath = computed(() => {
  let d = ''
  let pen = false
  jvm.value.forEach((v, i) => {
    if (v == null) { pen = false; return }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)} `
    pen = true
  })
  return d
})

const barMax = computed(() => Math.max(1, ...props.series.created, ...props.series.ended))
const barH = (v: number) => (v / barMax.value) * (zeroY - BAR_Y0 - 2)

const timeTicks = computed(() => {
  const count = Math.min(6, n.value)
  const ticks: { x: number; label: string }[] = []
  for (let k = 0; k < count; k++) {
    const i = count === 1 ? 0 : Math.round((k * (n.value - 1)) / (count - 1))
    ticks.push({ x: x(i), label: clock(props.windowStart + i * props.bucketMillis) })
  }
  return ticks
})

function clock(epoch: number): string {
  return new Date(epoch).toLocaleTimeString([], { hour12: false })
}

function onMove(event: MouseEvent) {
  const rect = (event.currentTarget as SVGRectElement).getBoundingClientRect()
  const px = event.clientX - rect.left
  const i = Math.floor(px / step.value)
  hover.value = Math.max(0, Math.min(n.value - 1, i))
}

const tip = computed(() => {
  const i = hover.value
  if (i == null) return null
  const start = props.windowStart + i * props.bucketMillis
  const left = x(i)
  return {
    left,
    flip: left > width.value - 220,
    range: `${clock(start)} – ${clock(start + props.bucketMillis)}`,
    alive: props.series.alive[i],
    jvm: jvm.value[i],
    created: props.series.created[i],
    ended: props.series.ended[i],
  }
})
</script>

<template>
  <div ref="host" class="trend" @mouseleave="hover = null">
    <svg :width="width" :height="HEIGHT" :viewBox="`0 0 ${width} ${HEIGHT}`" role="img">
      <defs>
        <linearGradient :id="fillId" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stop-color="#2563eb" stop-opacity="0.22" />
          <stop offset="100%" stop-color="#2563eb" stop-opacity="0.02" />
        </linearGradient>
      </defs>

      <g class="grid">
        <line v-for="t in yTicks" :key="`g${t}`" :x1="PAD_L" :x2="width - PAD_R" :y1="y(t)" :y2="y(t)" />
        <text v-for="t in yTicks" :key="`t${t}`" :x="PAD_L - 8" :y="y(t) + 4" text-anchor="end">{{ t.toLocaleString() }}</text>
        <text :x="PAD_L - 8" :y="BAR_Y0 + 4" text-anchor="end">+{{ barMax }}</text>
        <text :x="PAD_L - 8" :y="zeroY + 4" text-anchor="end">0</text>
        <text :x="PAD_L - 8" :y="BAR_Y1 + 4" text-anchor="end">−{{ barMax }}</text>
        <line class="zero" :x1="PAD_L" :x2="width - PAD_R" :y1="zeroY" :y2="zeroY" />
      </g>

      <g :key="animateKey" class="draw">
        <path class="alive-area" :d="aliveArea" :fill="`url(#${fillId})`" />
        <path class="alive-line" :d="alivePath" />
        <path v-if="hasJvm" class="jvm-line" :d="jvmPath" />
        <g class="bars">
          <template v-for="(v, i) in series.created" :key="`c${i}`">
            <rect v-if="v > 0" class="bar created" :x="x(i) - barW / 2" :y="zeroY - barH(v)" :width="barW" :height="barH(v)" rx="1.5" />
          </template>
          <template v-for="(v, i) in series.ended" :key="`e${i}`">
            <rect v-if="v > 0" class="bar ended" :x="x(i) - barW / 2" :y="zeroY" :width="barW" :height="barH(v)" rx="1.5" />
          </template>
        </g>
      </g>

      <g class="axis">
        <text v-for="(t, k) in timeTicks" :key="k" :x="t.x" :y="AXIS_Y" text-anchor="middle">{{ t.label }}</text>
      </g>

      <g v-if="tip" class="cursor">
        <line :x1="tip.left" :x2="tip.left" :y1="TOP_Y0 - 4" :y2="BAR_Y1 + 4" />
        <circle :cx="tip.left" :cy="y(tip.alive)" r="4" class="dot-alive" />
        <circle v-if="tip.jvm != null" :cx="tip.left" :cy="y(tip.jvm)" r="3.5" class="dot-jvm" />
      </g>

      <rect class="hit" :x="PAD_L" :y="0" :width="plotW" :height="BAR_Y1 + 8" @mousemove="onMove" />
    </svg>

    <div v-if="tip" class="tip" :class="{ flip: tip.flip }" :style="{ left: `${tip.left}px` }">
      <div class="tip-time">{{ tip.range }}</div>
      <div class="tip-row"><i class="sw alive"></i>存活线程<b>{{ tip.alive.toLocaleString() }}</b></div>
      <div v-if="tip.jvm != null" class="tip-row"><i class="sw jvm"></i>JVM 活跃<b>{{ tip.jvm.toLocaleString() }}</b></div>
      <div class="tip-row"><i class="sw created"></i>新建<b class="pos">+{{ tip.created }}</b></div>
      <div class="tip-row"><i class="sw ended"></i>销毁<b class="neg">−{{ tip.ended }}</b></div>
    </div>
  </div>
</template>

<style scoped>
.trend { position: relative; width: 100%; user-select: none; }
svg { display: block; overflow: visible; }
.grid line { stroke: #eef0f4; stroke-width: 1; }
.grid line.zero { stroke: #cbd2dc; }
.grid text, .axis text { fill: var(--ts-text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.alive-line { fill: none; stroke: #2563eb; stroke-width: 2; stroke-linejoin: round; }
.jvm-line { fill: none; stroke: #7c3aed; stroke-width: 1.5; stroke-dasharray: 5 4; }
.bar.created { fill: #16a34a; }
.bar.ended { fill: #dc2626; }
.draw .alive-line { stroke-dasharray: 4000; stroke-dashoffset: 4000; animation: draw 900ms ease-out forwards; }
.draw .alive-area, .draw .jvm-line { opacity: 0; animation: fade 600ms ease-out 200ms forwards; }
.draw .bar { transform-box: fill-box; animation: grow 520ms cubic-bezier(.2,.8,.2,1) both; }
.draw .bar.created { transform-origin: bottom; }
.draw .bar.ended { transform-origin: top; }
@keyframes draw { to { stroke-dashoffset: 0; } }
@keyframes fade { to { opacity: 1; } }
@keyframes grow { from { transform: scaleY(0); } to { transform: scaleY(1); } }
.cursor line { stroke: #94a3b8; stroke-width: 1; stroke-dasharray: 3 3; }
.dot-alive { fill: #fff; stroke: #2563eb; stroke-width: 2; }
.dot-jvm { fill: #fff; stroke: #7c3aed; stroke-width: 2; }
.hit { fill: transparent; cursor: crosshair; }
.tip {
  position: absolute;
  top: 8px;
  transform: translateX(14px);
  min-width: 176px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.97);
  border: var(--ts-border);
  border-radius: 10px;
  box-shadow: var(--ts-shadow-lg);
  font-size: 12px;
  pointer-events: none;
  z-index: 2;
}
.tip.flip { transform: translateX(calc(-100% - 14px)); }
.tip-time { color: var(--ts-text-secondary); font-variant-numeric: tabular-nums; margin-bottom: 6px; }
.tip-row { display: flex; align-items: center; gap: 6px; line-height: 20px; color: var(--ts-text-primary); }
.tip-row b { margin-left: auto; font-variant-numeric: tabular-nums; }
.tip-row b.pos { color: #16a34a; }
.tip-row b.neg { color: #dc2626; }
.sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
.sw.alive { background: #2563eb; }
.sw.jvm { background: #7c3aed; }
.sw.created { background: #16a34a; }
.sw.ended { background: #dc2626; }
</style>
