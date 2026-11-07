import { reactive, watch } from 'vue'

/**
 * 卡片折叠状态，按 storageKey 持久化在 localStorage，换报告后保持不变。
 * titleClick 用在卡片标题上：点到标题里的按钮、提示图标、输入框时不折叠。
 */
export function useFoldState(storageKey: string) {
  let initial: Record<string, boolean> = {}
  try {
    initial = JSON.parse(localStorage.getItem(storageKey) ?? '{}') ?? {}
  } catch {
    // 配置损坏时全部展开
  }
  const folded = reactive<Record<string, boolean>>(initial)

  watch(folded, () => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(folded))
    } catch {
      // 隐私模式 / 配额超限时忽略
    }
  })

  function toggle(key: string) {
    folded[key] = !folded[key]
  }

  function titleClick(key: string, event: MouseEvent) {
    if ((event.target as HTMLElement).closest('.hint, button, a, input, select')) return
    toggle(key)
  }

  return { folded, toggle, titleClick }
}
