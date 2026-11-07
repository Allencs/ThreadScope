<script setup lang="ts">
import { computed } from 'vue'
import HintTip from '@/components/common/HintTip.vue'
import { JFR_EVENT_INFO, type JfrSourceRef } from '@/utils/jfrEvents'

/** 标注一块数据来自哪些 JFR 事件：显示第一个事件名，悬停列出全部事件、用到的字段和事件含义。 */
const props = withDefaults(defineProps<{ events: JfrSourceRef[]; plain?: boolean }>(), { plain: false })

const names = computed(() => [...new Set(props.events.map(e => e.event))])
</script>

<template>
  <HintTip v-if="events.length" heading="JFR 数据源" :width="440">
    <template #trigger>
      <span class="src" :class="{ plain }">
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5c-3 0-5.5.9-5.5 2.25v8.5C2.5 13.6 5 14.5 8 14.5s5.5-.9 5.5-2.25v-8.5C13.5 2.4 11 1.5 8 1.5Zm0 1.25c2.6 0 4.25.7 4.25 1s-1.65 1-4.25 1-4.25-.7-4.25-1 1.65-1 4.25-1ZM3.75 5.5c1.1.5 2.6.75 4.25.75s3.15-.25 4.25-.75V8c0 .3-1.65 1-4.25 1S3.75 8.3 3.75 8V5.5Zm0 4.25c1.1.5 2.6.75 4.25.75s3.15-.25 4.25-.75v2.5c0 .3-1.65 1-4.25 1s-4.25-.7-4.25-1v-2.5Z" /></svg>
        <span class="name">{{ names[0] }}</span>
        <b v-if="names.length > 1" class="more">+{{ names.length - 1 }}</b>
      </span>
    </template>
    <ul class="src-list">
      <li v-for="(e, i) in events" :key="i">
        <code>{{ e.event }}</code>
        <span class="use">{{ e.use }}</span>
        <span v-if="JFR_EVENT_INFO[e.event]" class="info">{{ JFR_EVENT_INFO[e.event] }}</span>
      </li>
    </ul>
  </HintTip>
</template>

<style scoped>
.src {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 260px;
  padding: 1px 7px 1px 5px;
  border: 1px solid #dbe3f0;
  border-radius: 999px;
  background: #f8fafc;
  color: var(--ts-text-muted);
  font-family: var(--ts-font-display);
  font-size: 11px;
  font-weight: 500;
  line-height: 17px;
  transition: color var(--ts-transition), border-color var(--ts-transition), background var(--ts-transition);
}
.src svg { flex: none; width: 11px; height: 11px; fill: currentColor; opacity: 0.8; }
.src .name { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.src .more { flex: none; font-weight: 700; color: var(--ts-text-secondary); }
.src.plain { padding: 0; border: 0; background: transparent; font-size: 10.5px; max-width: 100%; }
.hint:hover .src, .hint.on .src, .hint:focus-visible .src { color: var(--ts-accent); border-color: #bfdbfe; background: var(--ts-accent-light); }
.hint:hover .src.plain, .hint.on .src.plain { background: transparent; }
.hint { max-width: 100%; min-width: 0; }

.src-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.src-list li { display: flex; flex-direction: column; gap: 1px; margin: 0; padding-left: 10px; border-left: 2px solid #dbeafe; }
.src-list code { align-self: flex-start; font-weight: 600; }
.src-list .use { color: var(--ts-text-primary); }
.src-list .info { color: var(--ts-text-muted); font-size: 12px; }
</style>
