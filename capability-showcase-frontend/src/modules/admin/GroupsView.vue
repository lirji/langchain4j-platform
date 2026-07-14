<script setup lang="ts">
/**
 * 用户组列表 /admin/groups（继承式 RBAC）。组数量少，一次拉全（无分页）。
 * 行：组名 / 说明 / 角色数 / 成员数 / 版本；行进 admin-group 编辑；新建入口。
 */
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminGroupsStore } from '../../stores/adminGroups'
import EmptyState from '../../components/common/EmptyState.vue'
import StatCard from '../../components/common/StatCard.vue'
import type { GroupView } from '../../types/admin'

const store = useAdminGroupsStore()
const router = useRouter()

onMounted(() => {
  if (store.status === 'idle') void store.load()
})

function openGroup(name: string): void {
  void router.push({ name: 'admin-group', params: { name } })
}
function newGroup(): void {
  void router.push({ name: 'admin-group', params: { name: 'new' } })
}
function rolesSummary(g: GroupView): string {
  return g.roles.length ? g.roles.join('、') : '（无角色）'
}
</script>

<template>
  <section class="gv">
    <div class="gv__top">
      <StatCard label="用户组总数" :value="store.groups.length" tone="primary" />
      <button type="button" class="btn btn--primary gv__new" @click="newGroup">＋ 新建用户组</button>
    </div>

    <EmptyState
      v-if="store.status === 'loading' && !store.groups.length"
      variant="loading"
      title="加载用户组…"
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
      v-else-if="!store.groups.length"
      icon="👪"
      title="暂无用户组"
      description="创建第一个用户组，以角色批量授权一批用户。"
      action-label="＋ 新建用户组"
      @action="newGroup"
    />

    <div v-else class="gv__table-wrap" role="region" aria-label="用户组列表" tabindex="0">
      <table class="gv__table">
        <thead>
          <tr>
            <th scope="col">组名</th>
            <th scope="col">说明</th>
            <th scope="col">角色</th>
            <th scope="col">成员数</th>
            <th scope="col">版本</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="g in store.groups"
            :key="g.name"
            class="gv__row"
            tabindex="0"
            @click="openGroup(g.name)"
            @keydown.enter="openGroup(g.name)"
          >
            <td class="gv__cell gv__cell--name">{{ g.name }}</td>
            <td class="gv__cell gv__cell--desc">{{ g.description || '—' }}</td>
            <td class="gv__cell" :title="rolesSummary(g)">
              <span aria-hidden="true">🛡 </span>{{ g.roles.length }} 项
            </td>
            <td class="gv__cell">
              <span class="gv__count" :data-in-use="g.memberCount > 0">{{ g.memberCount }} 人</span>
            </td>
            <td class="gv__cell gv__cell--ver">v{{ g.version }}</td>
            <td class="gv__cell gv__cell--actions" @click.stop>
              <button type="button" class="btn btn--sm btn--ghost" @click="openGroup(g.name)">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.gv {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.gv__top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.gv__new {
  margin-left: auto;
}
.gv__table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.gv__table-wrap:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.gv__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.gv__table th[scope='col'] {
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
.gv__row {
  cursor: pointer;
}
.gv__row:hover .gv__cell {
  background: var(--surface-2);
}
.gv__row:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.gv__cell {
  padding: var(--row-py) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.gv__cell--name {
  font-weight: var(--fw-semibold);
}
.gv__cell--desc {
  color: var(--text-muted);
  max-width: 32ch;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gv__cell--ver {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.gv__cell--actions {
  display: flex;
  gap: var(--space-2);
}
.gv__count {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--surface-3);
}
.gv__count[data-in-use='true'] {
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
</style>
