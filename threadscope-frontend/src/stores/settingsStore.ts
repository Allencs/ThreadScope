import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { CodeLayerConfig } from '@/types'
import type { SourceLinkRule } from '@/utils/sourceLink'
import { createClassifier, type CodeLayerClassifier } from '@/utils/codeLayer'
import * as api from '@/api/threadscope'

/**
 * 应用配置 (按应用区分业务包与源码仓库) — 持久化在浏览器 localStorage。
 */
export interface AppProfile {
  id: string
  name: string
  businessPackages: string[]
  sourceLinks: SourceLinkRule[]
}

const STORAGE_KEY = 'ts:settings:v1'

interface PersistedSettings {
  profiles: AppProfile[]
  activeProfileId: string | null
  foldFrameworkFrames: boolean
}

function newId(): string {
  return Math.random().toString(36).slice(2, 10)
}

function defaultProfile(): AppProfile {
  return { id: newId(), name: '默认应用', businessPackages: [], sourceLinks: [] }
}

function load(): PersistedSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const data = JSON.parse(raw) as Partial<PersistedSettings>
      if (Array.isArray(data.profiles) && data.profiles.length > 0) {
        return {
          profiles: data.profiles,
          activeProfileId: data.activeProfileId ?? data.profiles[0].id,
          foldFrameworkFrames: data.foldFrameworkFrames ?? true,
        }
      }
    }
  } catch {
    // 配置损坏时回退默认值
  }
  const p = defaultProfile()
  return { profiles: [p], activeProfileId: p.id, foldFrameworkFrames: true }
}

export const useSettingsStore = defineStore('settings', () => {
  const initial = load()
  const profiles = ref<AppProfile[]>(initial.profiles)
  const activeProfileId = ref<string | null>(initial.activeProfileId)
  const foldFrameworkFrames = ref(initial.foldFrameworkFrames)

  const settingsOpen = ref(false)
  const codeLayerConfig = ref<CodeLayerConfig | null>(null)
  /** 当前分析使用的业务包 (来自后端 overview)，应用配置未指定业务包时兜底 */
  const analysisBusinessPackages = ref<string[]>([])

  const activeProfile = computed<AppProfile>(() =>
    profiles.value.find(p => p.id === activeProfileId.value) ?? profiles.value[0],
  )

  const effectiveBusinessPackages = computed<string[]>(() => {
    if (activeProfile.value.businessPackages.length) return activeProfile.value.businessPackages
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

  function saveProfile(profile: AppProfile) {
    const idx = profiles.value.findIndex(p => p.id === profile.id)
    if (idx >= 0) profiles.value.splice(idx, 1, profile)
    else profiles.value.push(profile)
  }

  function createProfile(name: string): AppProfile {
    const p: AppProfile = { id: newId(), name, businessPackages: [], sourceLinks: [] }
    profiles.value.push(p)
    activeProfileId.value = p.id
    return p
  }

  function deleteProfile(id: string) {
    if (profiles.value.length <= 1) return
    profiles.value = profiles.value.filter(p => p.id !== id)
    if (activeProfileId.value === id) activeProfileId.value = profiles.value[0].id
  }

  watch([profiles, activeProfileId, foldFrameworkFrames], () => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        profiles: profiles.value,
        activeProfileId: activeProfileId.value,
        foldFrameworkFrames: foldFrameworkFrames.value,
      } satisfies PersistedSettings))
    } catch {
      // 隐私模式 / 配额超限时忽略
    }
  }, { deep: true })

  return {
    settingsOpen,
    profiles, activeProfileId, activeProfile, foldFrameworkFrames,
    codeLayerConfig, analysisBusinessPackages, effectiveBusinessPackages, businessInferred, classifier,
    loadCodeLayerConfig, saveProfile, createProfile, deleteProfile,
  }
})
