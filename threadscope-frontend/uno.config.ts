import { defineConfig, presetUno, presetIcons } from 'unocss'

export default defineConfig({
  presets: [
    presetUno(),
    presetIcons({
      scale: 1.2,
      cdn: 'https://esm.sh/',
    }),
  ],
  theme: {
    colors: {
      ts: {
        accent: '#58a6ff',
        danger: '#f85149',
        warning: '#d29922',
        success: '#3fb950',
      },
    },
  },
})
