<script setup lang="ts">
/**
 * Casdoor OIDC 顶层重定向回调页（阶段④，public 全屏）。
 * onMounted 换 token、建立内存会话，成功还原 returnTo（原 state），失败展示错误 + 重新登录出口。
 * 注：隐藏 iframe 的静默续期回调不走本组件，由 main.ts 在挂载前短路处理。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '../../components/common/EmptyState.vue'
import { useAuthStore } from '../../stores/auth'
import { sanitizeRedirect } from '../../router'
import { humanizeOidcCallbackError } from '../../api/errors'

const router = useRouter()
const auth = useAuthStore()
const failed = ref(false)
const message = ref('')

onMounted(async () => {
  try {
    const returnTo = await auth.handleOidcCallback()
    await router.replace(sanitizeRedirect(returnTo) ?? '/')
  } catch (e) {
    failed.value = true
    // 常见：state/nonce 不匹配（CSRF 防护）、code 过期、用户取消。人话化，不泄露内部细节。
    message.value = humanizeOidcCallbackError(e)
  }
})

function retry(): void {
  void auth.startOidcLogin('/')
}
</script>

<template>
  <div class="callback">
    <EmptyState
      v-if="!failed"
      variant="loading"
      title="正在完成登录…"
      description="正在与 Casdoor 交换凭证，请稍候。"
    />
    <EmptyState
      v-else
      variant="error"
      title="登录未完成"
      :description="message"
      action-label="重新登录"
      @action="retry"
    />
  </div>
</template>

<style scoped>
.callback {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: var(--space-4);
}
</style>
