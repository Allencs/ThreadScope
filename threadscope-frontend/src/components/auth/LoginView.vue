<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeRedirect, useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMsg = ref('')

async function submit() {
  if (!username.value.trim() || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  submitting.value = true
  errorMsg.value = ''
  try {
    const user = await auth.login(username.value.trim(), password.value)
    const target = safeRedirect(route.query.redirect)
    if (user.mustChangePassword) {
      await router.replace({ name: 'change-password', query: target === '/' ? {} : { redirect: target } })
    } else {
      await router.replace(target)
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '登录失败'
    password.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <form class="auth-card" @submit.prevent="submit">
      <div class="brand">
        <svg width="32" height="32" viewBox="0 0 64 64" fill="none" aria-hidden="true">
          <rect x="2" y="2" width="60" height="60" rx="16" fill="url(#loginBrandGrad)" />
          <path d="M20 21 L32 43 L44 21" stroke="#fff" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" fill="none" />
          <circle cx="32" cy="15" r="3" fill="#fff" />
          <defs>
            <linearGradient id="loginBrandGrad" x1="0" y1="0" x2="64" y2="64">
              <stop offset="0" stop-color="#2563eb" />
              <stop offset="1" stop-color="#7c3aed" />
            </linearGradient>
          </defs>
        </svg>
        <span>ThreadScope</span>
      </div>
      <h1>登录</h1>

      <label>
        <span>用户名</span>
        <input v-model="username" type="text" autocomplete="username" autofocus :disabled="submitting" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" :disabled="submitting" />
      </label>

      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <button class="primary" type="submit" :disabled="submitting">{{ submitting ? '登录中…' : '登录' }}</button>
      <p class="hint">没有账号或忘记密码，请联系管理员</p>
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
  background:
    radial-gradient(circle at 20% 15%, rgba(37, 99, 235, 0.08), transparent 45%),
    radial-gradient(circle at 80% 85%, rgba(124, 58, 237, 0.07), transparent 45%),
    var(--ts-bg-primary);
  overflow: auto;
}
.auth-card {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 32px 30px 26px;
  background: var(--ts-bg-surface);
  border: var(--ts-border);
  border-radius: var(--ts-radius-xl);
  box-shadow: var(--ts-shadow-lg);
}
.brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; margin-bottom: 6px; }
h1 { font-size: var(--ts-font-size-2xl); font-weight: 680; letter-spacing: -0.01em; }
label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--ts-text-secondary); }
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
  margin-top: 4px;
  border: 0;
  border-radius: var(--ts-radius-md);
  padding: 10px 14px;
  font: inherit;
  font-weight: 600;
  color: #fff;
  background: var(--ts-accent);
  cursor: pointer;
  transition: opacity var(--ts-transition);
}
.primary:disabled { opacity: 0.6; cursor: default; }
.hint { text-align: center; color: var(--ts-text-muted); font-size: 12px; }
</style>
