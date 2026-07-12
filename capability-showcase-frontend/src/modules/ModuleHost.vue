<script setup lang="ts">
import { computed, type Component } from 'vue'
import GenericModuleView from './GenericModuleView.vue'
import ChatConsoleView from './chat/ChatConsoleView.vue'
import AsyncMonitorView from './tasks/AsyncMonitorView.vue'
import WorkflowDeskView from './workflow/WorkflowDeskView.vue'
import MultimodalConsoleView from './multimodal/MultimodalConsoleView.vue'
import InteropEvalView from './interop/InteropEvalView.vue'
import ChannelConsoleView from './channel/ChannelConsoleView.vue'
import RagWorkspaceView from './rag/RagWorkspaceView.vue'
import AgentLabView from './agent/AgentLabView.vue'
import AnalyticsLabView from './analytics/AnalyticsLabView.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()

// 有状态 / 领域专用视图；其余走数据驱动的 GenericModuleView。
const SPECIALIZED: Record<string, Component> = {
  chat: ChatConsoleView,
  rag: RagWorkspaceView,
  agent: AgentLabView,
  tasks: AsyncMonitorView,
  analytics: AnalyticsLabView,
  workflow: WorkflowDeskView,
  multimodal: MultimodalConsoleView,
  'interop-eval': InteropEvalView,
  channel: ChannelConsoleView,
}

const view = computed<Component>(() => SPECIALIZED[props.moduleId] ?? GenericModuleView)
</script>

<template>
  <component :is="view" :key="moduleId" :module-id="moduleId" :cap-id="capId" />
</template>
