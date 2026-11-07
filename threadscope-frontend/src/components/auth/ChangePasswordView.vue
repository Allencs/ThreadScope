<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeRedirect, useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMsg = ref('')

const forced = computed(() => auth.user?.mustChangePassword === true)

/** 与后端 UserAccountService.validatePassword 一致，提前提示 */
function localCheck(): string | null {
  const pwd = newPassword.value
  if (pwd.length < 8) return '新密码至少 8 位'
  if (!/[A-Za-z]/.test(pwd) || !/\d/.test(pwd)) return '新密码需同时包含字母和数字'
  if (pwd !== confirmPassword.value) return '两次输入的新密码不一致'
  return null
}

async function submit() {
  errorMsg.value = localCheck() ?? ''
  if (errorMsg.value) return
  submitting.value = true
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '修改失败'
  } finally {
    submitting.value = false
  }
}

async function signOut() {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="auth-page">
    <form class="auth-card" @submit.prevent="submit">
      <button v-if="!forced" class="back" type="button" @click="router.push('/')">← 首页</button>
      <h1>修改密码</h1>
      <p v-if="forced" class="notice">当前是管理员分配的初始密码，请先设置自己的密码再继续使用。</p>
      <p class="sub">账号 {{ auth.user?.username }} · 修改后其他已登录的设备需要重新登录</p>

      <label>
        <span>当前密码</span>
        <input v-model="currentPassword" type="password" autocomplete="current-password" :disabled="submitting" />
      </label>
      <label>
        <span>新密码</span>
        <input v-model="newPassword" type="password" autocomplete="new-password" :disabled="submitting" />
        <small>至少 8 位，同时包含字母和数字，不能包含用户名</small>
      </label>
      <label>
        <span>确认新密码</span>
        <input v-model="confirmPassword" type="password" autocomplete="new-password" :disabled="submitting" />
      </label>

      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <button class="primary" type="submit" :disabled="submitting">{{ submitting ? '保存中…' : '保存新密码' }}</button>
      <button v-if="forced" class="link" type="button" @click="signOut">退出登录</button>
    </form>
  </div>
</template>

<style scoped>
.auth-page {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--ts-bg-primary);
  overflow: auto;
}
.auth-card {
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 28px 30px 24px;
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-xl);
  box-shadow: var(--ts-shadow-lg);
}
h1 { font-size: var(--ts-font-size-xl); font-weight: 680; }
.sub { margin-top: -8px; color: var(--ts-text-secondary); font-size: var(--ts-font-size-sm); }
.notice {
  color: var(--ts-warning);
  background: var(--ts-warning-light);
  border-radius: var(--ts-radius-sm);
  padding: 8px 10px;
  font-size: 13px;
}
label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--ts-text-secondary); }
small { color: var(--ts-text-muted); font-size: 12px; }
input {
  font: inherit;
  font-size: var(--ts-font-size-md);
  color: var(--ts-text-primary);
  padding: 9px 12px;
  border: var(--ts-border);
  border-radius: var(--ts-radius-md);
  background: var(--ts-bg-secondary);
  transition: border-color var(--ts-transition), box-shadow var(--ts-transition);
}
input:focus { outline: none; border-color: var(--ts-accent); box-shadow: var(--ts-shadow-ring); }
.error {
  color: var(--ts-danger);
  background: var(--ts-danger-light);
  border-radius: var(--ts-radius-sm);
  padding: 8px 10px;
  font-size: 13px;
}
.primary {
  border: 0;
  border-radius: var(--ts-radius-md);
  padding: 10px 14px;
  font: inherit;
  font-weight: 600;
  color: #fff;
  background: var(--ts-accent);
  cursor: pointer;
}
.primary:disabled { opacity: 0.6; cursor: default; }
.back, .link {
  align-self: flex-start;
  border: 0;
  background: transparent;
  color: var(--ts-text-secondary);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
}
.link { align-self: center; }
.back:hover, .link:hover { color: var(--ts-accent); }
</style>
