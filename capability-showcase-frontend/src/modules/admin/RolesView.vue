<script setup lang="ts">
/**
 * 角色列表 /admin/roles。角色数量少，一次拉全（无分页）。
 * 行：name / scopes 概要 / assignedUserCount（在用高亮） / version；行进 admin-role；新建入口。
 */
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminRolesStore } from '../../stores/adminRoles'
import EmptyState from '../../components/common/EmptyState.vue'
import StatCard from '../../components/common/StatCard.vue'
import type { RoleView } from '../../types/admin'

const store = useAdminRolesStore()
const router = useRouter()

onMounted(() => {
  if (store.status === 'idle') void store.load()
})

function openRole(name: string): void {
  void router.push({ name: 'admin-role', params: { name } })
}
function newRole(): void {
  void router.push({ name: 'admin-role', params: { name: 'new' } })
}
function scopesSummary(r: RoleView): string {
  return r.scopes.join('、')
}
</script>

<template>
  <section class="rv">
    <div class="rv__top">
      <StatCard label="角色总数" :value="store.roles.length" tone="primary" />
      <button type="button" class="btn btn--primary rv__new" @click="newRole">＋ 新建角色</button>
    </div>

    <EmptyState
      v-if="store.status === 'loading' && !store.roles.length"
      variant="loading"
      title="加载角色…"
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
      v-else-if="!store.roles.length"
      icon="🛡"
      title="暂无角色"
      description="创建第一个角色以组合 scope 权限。"
      action-label="＋ 新建角色"
      @action="newRole"
    />

    <div v-else class="rv__table-wrap" role="region" aria-label="角色列表" tabindex="0">
      <table class="rv__table">
        <thead>
          <tr>
            <th scope="col">角色名</th>
            <th scope="col">说明</th>
            <th scope="col">scopes</th>
            <th scope="col">绑定用户</th>
            <th scope="col">版本</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in store.roles"
            :key="r.name"
            class="rv__row"
            tabindex="0"
            @click="openRole(r.name)"
            @keydown.enter="openRole(r.name)"
          >
            <td class="rv__cell rv__cell--name">{{ r.name }}</td>
            <td class="rv__cell rv__cell--desc">{{ r.description || '—' }}</td>
            <td class="rv__cell" :title="scopesSummary(r)">
              <span aria-hidden="true">🔑 </span>{{ r.scopes.length }} 项
            </td>
            <td class="rv__cell">
              <span class="rv__count" :data-in-use="r.assignedUserCount > 0">{{ r.assignedUserCount }} 人</span>
            </td>
            <td class="rv__cell rv__cell--ver">v{{ r.version }}</td>
            <td class="rv__cell rv__cell--actions" @click.stop>
              <button type="button" class="btn btn--sm btn--ghost" @click="openRole(r.name)">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.rv {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.rv__top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.rv__new {
  margin-left: auto;
}
.rv__table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.rv__table-wrap:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.rv__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.rv__table th[scope='col'] {
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
.rv__row {
  cursor: pointer;
}
.rv__row:hover .rv__cell {
  background: var(--surface-2);
}
.rv__row:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.rv__cell {
  padding: var(--row-py) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.rv__cell--name {
  font-weight: var(--fw-semibold);
}
.rv__cell--desc {
  color: var(--text-muted);
  max-width: 32ch;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rv__cell--ver {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.rv__cell--actions {
  display: flex;
  gap: var(--space-2);
}
.rv__count {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--surface-3);
}
.rv__count[data-in-use='true'] {
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
</style>
