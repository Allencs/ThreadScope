import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/threadscope'
import type { CurrentUser } from '@/api/threadscope'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  /** 是否已向后端确认过登录状态（页面刷新后第一次路由跳转前确认） */
  const loaded = ref(false)

  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  async function refresh(): Promise<CurrentUser | null> {
    user.value = await api.fetchCurrentUser()
    loaded.value = true
    return user.value
  }

  async function login(username: string, password: string) {
    user.value = await api.login(username, password)
    loaded.value = true
    return user.value
  }

  async function logout() {
    try {
      await api.logout()
    } finally {
      user.value = null
    }
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    user.value = await api.changePassword(currentPassword, newPassword)
  }

  function markLoggedOut() {
    user.value = null
  }

  function markPasswordChangeRequired() {
    if (user.value) user.value = { ...user.value, mustChangePassword: true }
  }

  return { user, loaded, isAdmin, refresh, login, logout, changePassword, markLoggedOut, markPasswordChangeRequired }
})

/** 登录后跳回原页面；只接受站内路径，防止 ?redirect= 被用来跳到外站 */
export function safeRedirect(raw: unknown): string {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return '/'
  return raw
}
