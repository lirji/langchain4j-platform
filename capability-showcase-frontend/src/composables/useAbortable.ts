import { onScopeDispose, ref } from 'vue'

/** 管理一个可中止操作的 AbortController 生命周期（组件卸载时自动中止）。 */
export function useAbortable() {
  const controller = ref<AbortController | null>(null)

  function fresh(): AbortController {
    controller.value?.abort()
    const c = new AbortController()
    controller.value = c
    return c
  }

  function abort(): void {
    controller.value?.abort()
    controller.value = null
  }

  onScopeDispose(abort)

  return { controller, fresh, abort }
}
