# ThreadScope

Java 线程 Dump 智能分析平台。上传或粘贴 JVM Thread Dump 文本，即可获得死锁检测、线程池洞察、方法热点、堆栈聚合等可视化分析结果。

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Java 21 · Spring Boot 3.3 · Gradle (Kotlin DSL) · Caffeine Cache |
| 前端 | Vue 3 · TypeScript · Vite · Naive UI · ECharts · UnoCSS · Pinia |

## 项目结构

```
thread_scope/
├── threadscope-backend/       # Spring Boot 后端服务
│   └── src/main/java/com/threadscope/
│       ├── controller/        # REST 接口
│       ├── service/           # 业务编排
│       ├── engine/
│       │   ├── parser/        # Thread Dump 词法/语义解析
│       │   ├── analyzer/      # 死锁、线程池、热点、健康度分析
│       │   └── pattern/       # Dump 正则模式
│       ├── model/             # 领域模型
│       └── dto/               # 传输对象
├── threadscope-frontend/      # Vue 3 前端
│   ├── src/
│   │   ├── components/        # 业务组件 (仪表盘/线程/锁/热点/聚合)
│   │   ├── api/               # 后端 API 调用
│   │   ├── stores/            # Pinia 状态管理
│   │   ├── router/            # 路由
│   │   └── types/             # TypeScript 类型
│   ├── Dockerfile
│   └── nginx.conf
├── docker-compose.yml
└── README.md
```

## 核心功能

- **Thread Dump 解析** — 词法 + 语义两阶段解析，支持最多 50,000 个线程
- **死锁检测** — 构建锁等待图，自动发现循环依赖
- **线程池洞察** — 识别常见线程池并统计活跃/等待线程比例
- **方法热点分析** — 聚合栈帧，定位高频调用方法
- **堆栈聚合** — 将相同调用栈的线程自动分组
- **健康度报告** — 综合评估 Thread Dump 的健康状况

## 快速开始

### 环境要求

- JDK 21+
- Node.js 18+
- npm 9+

### 启动后端

```bash
cd threadscope-backend
./gradlew bootRun
```

服务默认监听 `http://localhost:8080`。

### 启动前端

```bash
cd threadscope-frontend
npm install
npm run dev
```

开发服务器启动后打开浏览器访问即可。

### 构建生产版本

```bash
# 前端
cd threadscope-frontend
npm run build          # 产物输出至 dist/

# 后端
cd threadscope-backend
./gradlew build        # 产物输出至 build/libs/
```

## Docker 部署

### 环境要求

- Docker 20+
- Docker Compose v2+

### 一键启动

```bash
docker compose up -d --build
```

启动完成后访问 `http://<服务器IP>:5173` 即可使用。

### 停止服务

```bash
docker compose down
```

### 自定义端口

修改 `docker-compose.yml` 中 frontend 的端口映射，例如改为 `8888:80`：

```yaml
  frontend:
    ports:
      - "8888:80"
```

默认端口为 `5173`。

## License

MIT
