<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'

/** 悬停显示的说明：不带 label 时是一个 ⓘ 图标，带 label 时是一枚提示标签。 */
const props = withDefaults(defineProps<{
  tone?: 'info' | 'warn' | 'legacy'
  label?: string
  heading?: string
  width?: number
}>(), { tone: 'info', label: '', heading: '', width: 400 })

const anchor = ref<HTMLElement | null>(null)
const open = ref(false)
const pos = ref({ top: 0, left: 0, arrow: 20, above: false })
let timer = 0

function place() {
  const el = anchor.value
  if (!el) return
  const r = el.getBoundingClientRect()
  const w = Math.min(props.width, window.innerWidth - 16)
  const left = Math.max(8, Math.min(r.left - 16, window.innerWidth - w - 8))
  const above = r.bottom + 280 > window.innerHeight && r.top > 300
  pos.value = { top: above ? r.top - 10 : r.bottom + 10, left, arrow: r.left + r.width / 2 - left, above }
  open.value = true
  window.addEventListener('scroll', close, true)
}

function show() {
  window.clearTimeout(timer)
  timer = window.setTimeout(place, open.value ? 0 : 140)
}

function hide() {
  window.clearTimeout(timer)
  timer = window.setTimeout(close, 160)
}

function close() {
  window.clearTimeout(timer)
  open.value = false
  window.removeEventListener('scroll', close, true)
}

onBeforeUnmount(close)
</script>

<template>
  <span
    ref="anchor"
    class="hint"
    :class="[`tone-${tone}`, { chip: !!label, on: open }]"
    tabindex="0"
    @mouseenter="show"
    @mouseleave="hide"
    @focus="show"
    @blur="hide"
  >
    <i class="icon" aria-hidden="true">{{ tone === 'info' ? 'i' : '!' }}</i>
    <span v-if="label" class="label">{{ label }}</span>
  </span>
  <Teleport to="body">
    <Transition name="hint-pop">
      <div
        v-if="open"
        class="hint-pop"
        :class="{ above: pos.above }"
        role="tooltip"
        :style="{ top: `${pos.top}px`, left: `${pos.left}px`, width: `${Math.min(width, 9999)}px`, '--arrow': `${pos.arrow}px` }"
        @mouseenter="show"
        @mouseleave="hide"
      >
        <h4 v-if="heading">{{ heading }}</h4>
        <slot />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  vertical-align: middle;
  cursor: help;
  outline: none;
  white-space: nowrap;
}
.icon {
  display: inline-grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  font-style: normal;
  font-size: 10.5px;
  font-weight: 800;
  font-family: Georgia, 'Times New Roman', serif;
  line-height: 1;
  color: var(--ts-text-muted);
  border: 1.3px solid currentColor;
  transition: color var(--ts-transition), background var(--ts-transition);
}
.tone-info:hover .icon, .tone-info.on .icon, .tone-info:focus-visible .icon { color: var(--ts-accent); }
.chip { padding: 2px 9px 2px 5px; border-radius: 999px; font-size: 12px; font-weight: 600; line-height: 18px; }
.chip .icon { width: 15px; height: 15px; border: 0; color: #fff; font-family: inherit; font-size: 10px; }
.tone-warn.chip { background: #fef2f2; color: #b91c1c; }
.tone-warn.chip .icon { background: #ef4444; }
.tone-warn.chip:hover, .tone-warn.chip.on { background: #fee2e2; }
.tone-legacy.chip { background: #fffbeb; color: #92400e; }
.tone-legacy.chip .icon { background: #f59e0b; }
.tone-legacy.chip:hover, .tone-legacy.chip.on { background: #fef3c7; }

.hint-pop {
  position: fixed;
  z-index: 1000;
  max-width: calc(100vw - 16px);
  padding: 14px 16px;
  background: #fff;
  border: var(--ts-border);
  border-radius: 12px;
  box-shadow: 0 16px 40px -12px rgba(15, 23, 42, 0.28);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ts-text-secondary);
  white-space: normal;
}
.hint-pop.above { transform: translateY(-100%); }
.hint-pop::before {
  content: '';
  position: absolute;
  top: -6px;
  left: calc(var(--arrow) - 5px);
  width: 10px;
  height: 10px;
  background: #fff;
  border-left: var(--ts-border);
  border-top: var(--ts-border);
  transform: rotate(45deg);
}
.hint-pop.above::before { top: auto; bottom: -6px; transform: rotate(225deg); }
.hint-pop h4 { margin: 0 0 6px; font-size: 13.5px; color: var(--ts-text-primary); }
.hint-pop :slotted(p) { margin: 0 0 6px; }
.hint-pop :slotted(p:last-child) { margin-bottom: 0; }
.hint-pop :slotted(b) { color: var(--ts-text-primary); }
.hint-pop :slotted(ul) { margin: 0 0 6px; padding-left: 18px; }
.hint-pop :slotted(li) { margin: 2px 0; }
.hint-pop :slotted(code) {
  font-family: var(--ts-font-display);
  font-size: 11.5px;
  background: var(--ts-bg-inset);
  color: var(--ts-text-primary);
  padding: 0 4px;
  border-radius: 4px;
  word-break: break-all;
}
.hint-pop :slotted(.hint-note) { margin: 8px 0 0; padding: 8px 10px; border-radius: 8px; background: var(--ts-accent-light); color: #1e40af; }
.hint-pop-enter-active, .hint-pop-leave-active { transition: opacity 140ms ease, margin-top 140ms ease; }
.hint-pop-enter-from, .hint-pop-leave-to { opacity: 0; margin-top: -4px; }
</style>
