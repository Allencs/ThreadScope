<script setup lang="ts">
defineProps<{ folded?: boolean }>()
</script>

<template>
  <span class="fold-chev" :class="{ shut: folded }" :title="folded ? '展开' : '折叠'">
    <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.5 6 7.5 9 4.5" /></svg>
  </span>
</template>

<style scoped>
.fold-chev { width: 22px; height: 22px; flex: none; display: grid; place-items: center; border-radius: 6px; color: var(--ts-text-muted); }
.fold-chev svg { width: 13px; height: 13px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; transition: transform var(--ts-transition); }
.fold-chev.shut svg { transform: rotate(-90deg); }
</style>
