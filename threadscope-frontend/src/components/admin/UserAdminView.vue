<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createUser,
  deleteUser,
  fetchUsers,
  resetUserPassword,
  updateUser,
  type ManagedUser,
  type UserRole,
} from '@/api/threadscope'
import { useAuthStore } from '@/stores/authStore'
import UserMenu from '@/components/common/UserMenu.vue'

const router = useRouter()
const auth = useAuthStore()

const users = ref<ManagedUser[]>([])
const loading = ref(true)
const errorMsg = ref('')
const newUsername = ref('')
const newRole = ref<UserRole>('USER')
const creating = ref(false)
/** 临时密码只在创建/重置后显示这一次 */
const issued = ref<{ username: string; password: string } | null>(null)
const copied = ref(false)

const ROLE_LABEL: Record<UserRole, string> = { ADMIN: '管理员', USER: '普通用户' }

function formatTime(epoch: number | null): string {
  return epoch == null ? '—' : new Date(epoch).toLocaleString()
}

function isSelf(user: ManagedUser) {
  return user.username === auth.user?.username
}

async function run(action: () => Promise<void>) {
  errorMsg.value = ''
  try {
    await action()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '操作失败'
  }
}

async function load() {
  loading.value = true
  await run(async () => {
    users.value = await fetchUsers()
  })
  loading.value = false
}

function replace(updated: ManagedUser) {
  users.value = users.value.map(u => (u.id === updated.id ? updated : u))
}

function showIssued(username: string, password: string) {
  issued.value = { username, password }
  copied.value = false
}

async function create() {
  if (!newUsername.value.trim()) return
  creating.value = true
  await run(async () => {
    const result = await createUser(newUsername.value.trim(), newRole.value)
    users.value = [...users.value, result.user]
    showIssued(result.user.username, result.temporaryPassword)
    newUsername.value = ''
    newRole.value = 'USER'
  })
  creating.value = false
}

async function changeRole(user: ManagedUser, role: UserRole) {
  await run(async () => replace(await updateUser(user.id, { role })))
  if (errorMsg.value) await load()
}

async function toggleEnabled(user: ManagedUser) {
  const verb = user.enabled ? '停用' : '启用'
  if (!confirm(`${verb}账号「${user.username}」？${user.enabled ? '该账号会被立即登出。' : ''}`)) return
  await run(async () => replace(await updateUser(user.id, { enabled: !user.enabled })))
}

async function reset(user: ManagedUser) {
  if (!confirm(`重置「${user.username}」的密码？该账号会被立即登出，下次登录需使用新的临时密码并修改。`)) return
  await run(async () => {
    const result = await resetUserPassword(user.id)
    replace(result.user)
    showIssued(result.user.username, result.temporaryPassword)
  })
}

async function remove(user: ManagedUser) {
  if (!confirm(`删除账号「${user.username}」？此操作不可恢复，已上传的报告会保留。`)) return
  await run(async () => {
    await deleteUser(user.id)
    users.value = users.value.filter(u => u.id !== user.id)
  })
}

async function copyPassword() {
  if (!issued.value) return
  try {
    await navigator.clipboard.writeText(issued.value.password)
    copied.value = true
  } catch {
    // HTTP 页面下浏览器可能禁用剪贴板 API，用户手动选中复制即可
    copied.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-page">
    <header class="admin-bar">
      <button class="back" type="button" @click="router.push('/')">← 首页</button>
      <div>
        <h1>用户管理</h1>
        <p class="sub">管理员可管理账号并删除任何报告；普通用户可上传、查看全部分析，只能删除自己上传的报告</p>
      </div>
      <span class="spacer"></span>
      <UserMenu />
    </header>

    <div class="admin-body">
      <form class="create" @submit.prevent="create">
        <input v-model="newUsername" type="text" placeholder="新用户名（3~32 位小写字母、数字、._-）" autocomplete="off" />
        <select v-model="newRole">
          <option value="USER">普通用户</option>
          <option value="ADMIN">管理员</option>
        </select>
        <button class="primary" type="submit" :disabled="creating || !newUsername.trim()">创建账号</button>
      </form>

      <div v-if="issued" class="issued">
        <div>
          <strong>「{{ issued.username }}」的临时密码</strong>
          <p>只显示这一次，请通过安全渠道发给对方。对方首次登录后必须修改。</p>
        </div>
        <code>{{ issued.password }}</code>
        <button type="button" @click="copyPassword">{{ copied ? '已复制' : '复制' }}</button>
        <button type="button" class="close" @click="issued = null">关闭</button>
      </div>

      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
      <p v-if="loading" class="muted">正在加载…</p>

      <table v-else class="user-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>角色</th>
            <th>状态</th>
            <th>最近登录</th>
            <th>创建时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id" :class="{ disabled: !user.enabled }">
            <td>
              <span class="name">{{ user.username }}</span>
              <span v-if="isSelf(user)" class="tag">我</span>
              <span v-if="user.mustChangePassword" class="tag warn">待改初始密码</span>
            </td>
            <td>
              <select
                :value="user.role"
                :disabled="isSelf(user)"
                @change="changeRole(user, ($event.target as HTMLSelectElement).value as UserRole)"
              >
                <option v-for="(label, role) in ROLE_LABEL" :key="role" :value="role">{{ label }}</option>
              </select>
            </td>
            <td>
              <span :class="user.enabled ? 'on' : 'off'">{{ user.enabled ? '启用' : '已停用' }}</span>
            </td>
            <td class="mono">{{ formatTime(user.lastLoginAtEpochMs) }}</td>
            <td class="mono">{{ formatTime(user.createdAtEpochMs) }}</td>
            <td class="actions">
              <template v-if="!isSelf(user)">
                <button type="button" @click="toggleEnabled(user)">{{ user.enabled ? '停用' : '启用' }}</button>
                <button type="button" @click="reset(user)">重置密码</button>
                <button type="button" class="danger" @click="remove(user)">删除</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--ts-bg-primary);
}
.admin-bar {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 16px;
  padding: 16px 28px 14px;
  background: var(--ts-bg-secondary);
  border-bottom: var(--ts-border);
}
.admin-bar h1 { font-size: 18px; font-weight: 650; }
.sub { margin-top: 2px; color: var(--ts-text-secondary); font-size: 12.5px; }
.spacer { flex: 1; }
.back {
  align-self: center;
  border: 0;
  background: transparent;
  color: var(--ts-text-secondary);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}
.back:hover { color: var(--ts-accent); }
.admin-body { flex: 1; min-height: 0; overflow: auto; padding: 20px 28px 48px; display: flex; flex-direction: column; gap: 16px; }
.create { display: flex; gap: 8px; }
.create input, select {
  font: inherit;
  font-size: 13px;
  color: var(--ts-text-primary);
  padding: 7px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  background: var(--ts-bg-surface);
}
.create input { width: 320px; }
.create input:focus, select:focus { outline: none; border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.primary {
  border: 0;
  border-radius: var(--ts-radius-md);
  padding: 7px 14px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--ts-accent);
  cursor: pointer;
}
.primary:disabled { opacity: 0.5; cursor: default; }
.issued {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  border: 1px solid #bfdbfe;
  background: var(--ts-accent-light);
  border-radius: var(--ts-radius-lg);
  font-size: 13px;
}
.issued p { color: var(--ts-text-secondary); font-size: 12px; margin-top: 2px; }
.issued code {
  margin-left: auto;
  padding: 6px 12px;
  border-radius: var(--ts-radius-sm);
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  font-size: 15px;
  letter-spacing: 0.04em;
  user-select: all;
}
.issued button {
  border: var(--ts-border);
  background: var(--ts-bg-surface);
  border-radius: var(--ts-radius-md);
  padding: 6px 12px;
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}
.issued .close { border: 0; background: transparent; color: var(--ts-text-secondary); }
.error { color: var(--ts-danger); }
.muted { color: var(--ts-text-secondary); }
.user-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: 12px;
  overflow: hidden;
}
.user-table th, .user-table td { text-align: left; padding: 11px 14px; border-bottom: var(--ts-border); font-size: 13px; vertical-align: middle; }
.user-table th { color: var(--ts-text-secondary); font-weight: 600; font-size: 12px; background: var(--ts-bg-inset); }
.user-table tr.disabled td { color: var(--ts-text-muted); }
.name { font-weight: 650; }
.tag {
  margin-left: 6px;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--ts-radius-full);
  background: var(--ts-bg-elevated);
  color: var(--ts-text-secondary);
}
.tag.warn { background: var(--ts-warning-light); color: var(--ts-warning); }
.on { color: var(--ts-success); }
.off { color: var(--ts-text-muted); }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.actions { white-space: nowrap; text-align: right; }
.actions button {
  border: 0;
  background: transparent;
  color: var(--ts-text-secondary);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  margin-left: 8px;
}
.actions button:hover { color: var(--ts-accent); }
.actions .danger { color: var(--ts-danger); }
</style>
