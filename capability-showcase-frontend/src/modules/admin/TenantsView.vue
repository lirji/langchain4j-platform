<script setup lang="ts">
/**
 * 租户基础角色列表 /admin/tenants（继承式 RBAC）。租户数量少，一次拉全（无分页）。
 * 行：租户 / 基础角色概要 / 有效基础 scopes 数 / 成员数 / 版本；行进 admin-tenant 编辑。
 */
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminTenantsStore } from '../../stores/adminTenants'
import EmptyState from '../../components/common/EmptyState.vue'
import StatCard from '../../components/common/StatCard.vue'
import InfoNote from '../_shared/InfoNote.vue'
import type { TenantView } from '../../types/admin'

const store = useAdminTenantsStore()
const router = useRouter()

onMounted(() => {
  if (store.status === 'idle') void store.load()
})

function openTenant(tenant: string): void {
  void router.push({ name: 'admin-tenant', params: { tenant } })
}
function baseRolesSummary(t: TenantView): string {
  return t.baseRoles.length ? t.baseRoles.join('、') : '（无基础角色）'
}
</script>

<template>
  <section class="tv">
    <div class="tv__top">
      <StatCard label="租户总数" :value="store.tenants.length" tone="primary" />
    </div>

    <InfoNote tone="info">
      租户基础角色对该租户<strong>全体成员</strong>生效——其 scopes 会并入每位成员的有效权限（继承式 RBAC）。
    </InfoNote>

    <EmptyState
      v-if="store.status === 'loading' && !store.tenants.length"
      variant="loading"
      title="加载租户…"
    />
    <EmptyState
      v-else-if="store.status === 'error'"
      variant="error"
      title="加载失败"
      :description="store.error ?? '请稍后重试。'"
      action-label="重试"
      @action="store.load()"
    />
    <EmptyState
      v-else-if="!store.tenants.length"
      icon="🏢"
      title="暂无租户基础角色"
      description="尚未为任何租户配置基础角色。"
    />

    <div v-else class="tv__table-wrap" role="region" aria-label="租户列表" tabindex="0">
      <table class="tv__table">
        <thead>
          <tr>
            <th scope="col">租户</th>
            <th scope="col">基础角色</th>
            <th scope="col">有效基础 scopes</th>
            <th scope="col">成员数</th>
            <th scope="col">版本</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="t in store.tenants"
            :key="t.tenant"
            class="tv__row"
            tabindex="0"
            @click="openTenant(t.tenant)"
            @keydown.enter="openTenant(t.tenant)"
          >
            <td class="tv__cell tv__cell--name">{{ t.tenant }}</td>
            <td class="tv__cell" :title="baseRolesSummary(t)">
              <span v-if="!t.baseRoles.length" class="tv__muted">（无基础角色）</span>
              <template v-else>
                <span v-for="r in t.baseRoles.slice(0, 2)" :key="r" class="tv__role">{{ r }}</span>
                <span v-if="t.baseRoles.length > 2" class="tv__role tv__role--more">+{{ t.baseRoles.length - 2 }}</span>
              </template>
            </td>
            <td class="tv__cell" :title="t.effectiveBaseScopes.join('、')">
              <span aria-hidden="true">🔑 </span>{{ t.effectiveBaseScopes.length }} 项
            </td>
            <td class="tv__cell">
              <span class="tv__count" :data-in-use="t.memberCount > 0">{{ t.memberCount }} 人</span>
            </td>
            <td class="tv__cell tv__cell--ver">v{{ t.version }}</td>
            <td class="tv__cell tv__cell--actions" @click.stop>
              <button type="button" class="btn btn--sm btn--ghost" @click="openTenant(t.tenant)">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.tv {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.tv__top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.tv__table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.tv__table-wrap:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.tv__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.tv__table th[scope='col'] {
  position: sticky;
  top: 0;
  z-index: 1;
  text-align: left;
  padding: var(--row-py) var(--space-3);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.tv__row {
  cursor: pointer;
}
.tv__row:hover .tv__cell {
  background: var(--surface-2);
}
.tv__row:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.tv__cell {
  padding: var(--row-py) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.tv__cell--name {
  font-weight: var(--fw-semibold);
}
.tv__cell--ver {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.tv__cell--actions {
  display: flex;
  gap: var(--space-2);
}
.tv__muted {
  color: var(--text-subtle);
}
.tv__role {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
}
.tv__role--more {
  color: var(--text-muted);
  background: var(--surface-3);
  border-color: var(--border);
}
.tv__count {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--surface-3);
}
.tv__count[data-in-use='true'] {
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
</style>
