<script setup lang="ts">
/**
 * 管理域壳：标题 + 用户/角色子导航 + <RouterView/>。
 *
 * 防御性 v-if="auth.isAdmin"（守卫之外的兜底：会话中途失权/api-key 覆盖时也不渲染管理内容），
 * 否则内联 ForbiddenView。**不含任何 api-key 入口**（管理域 Bearer-only）。
 */
import { useAuthStore } from '../../stores/auth'
import ForbiddenView from './ForbiddenView.vue'
import InfoNote from '../_shared/InfoNote.vue'

const auth = useAuthStore()
</script>

<template>
  <ForbiddenView v-if="!auth.isAdmin" />
  <div v-else class="page page--wide admin">
    <header class="admin__head">
      <p class="eyebrow">平台管理</p>
      <h1 class="admin__title">管理中心</h1>
      <p class="admin__desc">
        管理全局用户的租户归属、角色分配与启停，以及角色的 scope 组合。管理写操作恒用账号会话（Bearer）。
      </p>
    </header>

    <nav class="admin__nav" aria-label="管理域导航">
      <RouterLink :to="{ name: 'admin-users' }" class="admin__tab">👥 用户管理</RouterLink>
      <RouterLink :to="{ name: 'admin-roles' }" class="admin__tab">🛡 角色管理</RouterLink>
    </nav>

    <InfoNote tone="info">
      管理写入若处于灰度关闭态，提交会返回“未开启”提示（只读浏览不受影响）。权限变更在目标用户下次刷新令牌后生效。
    </InfoNote>

    <div class="admin__body">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.admin__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.admin__title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
}
.admin__desc {
  font-size: var(--fs-sm);
  color: var(--text-muted);
  max-width: 82ch;
  line-height: var(--lh-base);
}
.admin__nav {
  display: flex;
  gap: var(--space-2);
  border-bottom: 1px solid var(--border);
  padding-bottom: var(--space-2);
}
.admin__tab {
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  border-radius: var(--radius);
  text-decoration: none;
}
.admin__tab:hover {
  background: var(--surface-2);
  color: var(--text);
}
.admin__tab.router-link-active {
  color: var(--primary);
  background: var(--primary-soft);
}
.admin__body {
  margin-top: var(--space-2);
}
</style>
