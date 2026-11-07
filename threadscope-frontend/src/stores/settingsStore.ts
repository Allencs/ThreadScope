import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { CodeLayerConfig } from '@/types'
import type { SourceLinkRule } from '@/utils/sourceLink'
import { createClassifier, type CodeLayerClassifier } from '@/utils/codeLayer'
import * as api from '@/api/threadscope'

/**
 * 代码归属配置 (业务包与源码仓库) — 持久化在浏览器 localStorage。
 */
export interface CodeProfile {
  businessPackages: string[]
  sourceLinks: SourceLinkRule[]
}

const STORAGE_KEY = 'ts:settings:v1'

interface PersistedSettings {
  profile: CodeProfile
  foldFrameworkFrames: boolean
}

/** 旧版按应用保存多份配置，迁移时保留当时选中的那份 */
interface LegacySettings {
  profiles?: (CodeProfile & { id: string })[]
  activeProfileId?: string | null
}

function load(): PersistedSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const data = JSON.parse(raw) as Partial<PersistedSettings> & LegacySettings
      const legacy = data.profiles?.find(p => p.id === data.activeProfileId) ?? data.profiles?.[0]
      const profile = data.profile ?? legacy
      return {
        profile: {
          businessPackages: profile?.businessPackages ?? [],
          sourceLinks: profile?.sourceLinks ?? [],
        },
        foldFrameworkFrames: data.foldFrameworkFrames ?? true,
      }
    }
  } catch {
    // 配置损坏时回退默认值
  }
  return { profile: { businessPackages: [], sourceLinks: [] }, foldFrameworkFrames: true }
}

export const useSettingsStore = defineStore('settings', () => {
  const initial = load()
  const profile = ref<CodeProfile>(initial.profile)
  const foldFrameworkFrames = ref(initial.foldFrameworkFrames)

  const settingsOpen = ref(false)
  const codeLayerConfig = ref<CodeLayerConfig | null>(null)
  /** 当前分析使用的业务包 (来自后端 overview)，设置里未指定业务包时兜底 */
  const analysisBusinessPackages = ref<string[]>([])

  const effectiveBusinessPackages = computed<string[]>(() => {
    if (profile.value.businessPackages.length) return profile.value.businessPackages
    if (analysisBusinessPackages.value.length) return analysisBusinessPackages.value
    return codeLayerConfig.value?.defaultBusinessPackages ?? []
  })

  /** 未配置任何业务包时，JDK/已知框架之外的代码都算业务代码 (推断) */
  const businessInferred = computed(() => effectiveBusinessPackages.value.length === 0)

  const classifier = computed<CodeLayerClassifier>(() =>
    createClassifier(codeLayerConfig.value, effectiveBusinessPackages.value),
  )

  let configLoading: Promise<void> | null = null
  function loadCodeLayerConfig(): Promise<void> {
    if (codeLayerConfig.value) return Promise.resolve()
    if (!configLoading) {
      configLoading = api.fetchCodeLayerConfig()
        .then(cfg => { codeLayerConfig.value = cfg })
        .catch(() => { configLoading = null })
    }
    return configLoading
  }

  function saveProfile(next: CodeProfile) {
    profile.value = next
  }

  watch([profile, foldFrameworkFrames], () => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        profile: profile.value,
        foldFrameworkFrames: foldFrameworkFrames.value,
      } satisfies PersistedSettings))
    } catch {
      // 隐私模式 / 配额超限时忽略
    }
  }, { deep: true })

  return {
    settingsOpen,
    profile, foldFrameworkFrames,
    codeLayerConfig, analysisBusinessPackages, effectiveBusinessPackages, businessInferred, classifier,
    loadCodeLayerConfig, saveProfile,
  }
})
