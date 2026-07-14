<script setup lang="ts">
/**
 * 模块图标：按 moduleId 渲染受控本地 inline SVG（线性、单色 currentColor）。
 * 绝不使用 v-html / 远程内容。未知模块回退通用网格图标。
 */
defineProps<{ name: string }>()
</script>

<template>
  <svg
    class="nav-mod-icon"
    width="15"
    height="15"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'chat'">
      <path d="M20 12.5a7 7 0 0 1-7 7 7.6 7.6 0 0 1-2.8-.5L5 20.5l1.4-4.1A7 7 0 1 1 20 12.5z" />
    </template>
    <template v-else-if="name === 'rag'">
      <path d="M12 3.5l8.5 4.5-8.5 4.5L3.5 8z" />
      <path d="M4 12l8 4.3 8-4.3" />
      <path d="M4 16l8 4.3 8-4.3" />
    </template>
    <template v-else-if="name === 'agent'">
      <rect x="7" y="7" width="10" height="10" rx="2" />
      <rect x="10.4" y="10.4" width="3.2" height="3.2" rx=".6" />
      <path d="M10 3.5v2.5M14 3.5v2.5M10 18v2.5M14 18v2.5M3.5 10H6M3.5 14H6M18 10h2.5M18 14h2.5" />
    </template>
    <template v-else-if="name === 'tasks'">
      <circle cx="12" cy="12" r="8" />
      <path d="M12 8v4.2l2.8 1.8" />
    </template>
    <template v-else-if="name === 'workflow'">
      <circle cx="7" cy="6" r="2.2" />
      <circle cx="7" cy="18" r="2.2" />
      <circle cx="17" cy="9" r="2.2" />
      <path d="M7 8.2v7.6" />
      <path d="M9.2 15.8H11c3 0 6-1.3 6-4.6" />
    </template>
    <template v-else-if="name === 'analytics'">
      <path d="M4 20h16" />
      <rect x="6" y="11" width="3" height="6" rx=".6" />
      <rect x="11" y="7" width="3" height="10" rx=".6" />
      <rect x="16" y="13" width="3" height="4" rx=".6" />
    </template>
    <template v-else-if="name === 'multimodal'">
      <rect x="4" y="4" width="16" height="16" rx="2.5" />
      <circle cx="9" cy="9.5" r="1.8" />
      <path d="M5 16.5l4-3.5 3.5 3 3-2.5 3.5 3" />
    </template>
    <template v-else-if="name === 'interop-eval'">
      <circle cx="10" cy="12" r="2" />
      <circle cx="17" cy="7" r="2" />
      <circle cx="17" cy="17" r="2" />
      <path d="M4 12h4" />
      <path d="M11.7 11l3.6-2.6" />
      <path d="M11.7 13l3.6 2.6" />
    </template>
    <template v-else-if="name === 'channel'">
      <path d="M21 4L11 14" />
      <path d="M21 4l-6.5 16.5-3.5-7.5L3.5 9.5z" />
    </template>
    <template v-else>
      <rect x="4" y="4" width="16" height="16" rx="2.5" />
      <path d="M9 4v3M15 4v3M4 9h3M4 15h3M17 9h3M17 15h3M9 17v3M15 17v3" />
    </template>
  </svg>
</template>

<style scoped>
.nav-mod-icon {
  display: block;
}
</style>
