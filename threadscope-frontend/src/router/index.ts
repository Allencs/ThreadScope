import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/components/upload/DropZone.vue'),
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
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
