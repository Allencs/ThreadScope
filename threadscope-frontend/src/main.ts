import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { router } from './router'
import App from './App.vue'

import './assets/styles/tokens.css'
import './assets/styles/global.css'
import './assets/styles/animations.css'

import 'uno.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
