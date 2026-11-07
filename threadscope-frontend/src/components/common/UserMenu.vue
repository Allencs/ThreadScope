<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const router = useRouter()

async function signOut() {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div v-if="auth.user" class="user-menu">
    <button v-if="auth.isAdmin" type="button" @click="router.push({ name: 'admin-users' })">用户管理</button>
    <span class="who" :title="auth.isAdmin ? '管理员' : '普通用户'">
      {{ auth.user.username }}<em v-if="auth.isAdmin">管理员</em>
    </span>
    <button type="button" @click="router.push({ name: 'change-password' })">修改密码</button>
    <button type="button" @click="signOut">退出</button>
  </div>
</template>

<style scoped>
.user-menu { display: flex; align-items: center; gap: 4px; }
.user-menu button {
  border: 0;
  background: transparent;
  padding: 7px 10px;
  border-radius: 8px;
  font: inherit;
  font-size: 13px;
  color: var(--ts-text-secondary);
  cursor: pointer;
  transition: background var(--ts-transition), color var(--ts-transition);
}
.user-menu button:hover { background: var(--ts-bg-hover); color: var(--ts-text-primary); }
.who {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 6px;
  padding: 4px 10px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-full);
  background: var(--ts-bg-surface);
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ts-text-primary);
}
.who em {
  font-style: normal;
  font-weight: 500;
  font-size: 11px;
  color: var(--ts-accent);
  background: var(--ts-accent-light);
  border-radius: var(--ts-radius-full);
  padding: 1px 6px;
}
</style>
