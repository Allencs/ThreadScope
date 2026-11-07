import { createRouter, createWebHistory } from 'vue-router'
import { onAuthFailure } from '@/api/threadscope'
import { useAuthStore } from '@/stores/authStore'

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要登录即可访问 */
    public?: boolean
    /** 仅管理员 */
    admin?: boolean
  }
}

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/components/auth/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/account/password',
    name: 'change-password',
    component: () => import('@/components/auth/ChangePasswordView.vue'),
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('@/components/admin/UserAdminView.vue'),
    meta: { admin: true },
  },
  {
    path: '/',
    name: 'home',
    component: () => import('@/components/upload/DropZone.vue'),
  },
  {
    path: '/jfr',
    name: 'jfr-history',
    component: () => import('@/components/jfr/JfrHistoryView.vue'),
  },
  {
    path: '/jfr/:reportId',
    name: 'jfr-report',
    component: () => import('@/components/jfr/JfrReportView.vue'),
  },
  {
    path: '/analysis/:analysisId',
    name: 'analysis',
    component: () => import('@/components/layout/AnalysisLayout.vue'),
    children: [
      {
        path: '',
        redirect: { name: 'dashboard' },
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/components/dashboard/DashboardView.vue'),
      },
      {
        path: 'threads',
        name: 'threads',
        component: () => import('@/components/threads/ThreadExplorer.vue'),
      },
      {
        path: 'locks',
        name: 'locks',
        component: () => import('@/components/locks/LockAnalyzer.vue'),
      },
      {
        path: 'pools',
        name: 'pools',
        component: () => import('@/components/pools/ThreadPoolInsights.vue'),
      },
      {
        path: 'aggregation',
        name: 'aggregation',
        component: () => import('@/components/aggregation/StackAggregation.vue'),
      },
      {
        path: 'hotspots',
        name: 'hotspots',
        component: () => import('@/components/hotspots/MethodHotspots.vue'),
      },
      {
        path: 'flame',
        name: 'flame',
        component: () => import('@/components/flame/FlameGraphView.vue'),
      },
      {
        path: 'compare',
        name: 'compare',
        component: () => import('@/components/comparison/ComparisonView.vue'),
      },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.loaded) {
    try {
      await auth.refresh()
    } catch {
      // 后端暂时不可用时按未登录处理，登录页会提示具体错误
      auth.markLoggedOut()
    }
  }
  if (to.meta.public) return true
  if (!auth.user) return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  if (auth.user.mustChangePassword && to.name !== 'change-password') {
    return { name: 'change-password', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.admin && !auth.isAdmin) return { name: 'home' }
  return true
})

onAuthFailure((reason) => {
  const auth = useAuthStore()
  const current = router.currentRoute.value
  if (reason === 'unauthenticated') {
    auth.markLoggedOut()
    if (!current.meta.public) router.replace({ name: 'login', query: { redirect: current.fullPath } })
  } else {
    auth.markPasswordChangeRequired()
    if (current.name !== 'change-password') router.replace({ name: 'change-password' })
  }
})
