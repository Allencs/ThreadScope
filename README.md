# ThreadScope

Java 线程 Dump 智能分析平台。上传或粘贴 JVM Thread Dump 文本，即可获得死锁检测、锁链根因定位、线程池洞察、方法热点、火焰图、多 Dump 差分对比等可视化分析结果。

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Java 21 (Virtual Threads) · Spring Boot 3.3 · Gradle (Kotlin DSL) · Caffeine Cache |
| 前端 | Vue 3 · TypeScript · Vite · UnoCSS · Pinia |

## 项目结构

```
thread_scope/
├── threadscope-backend/       # Spring Boot 后端服务
│   └── src/main/java/com/threadscope/
│       ├── controller/        # REST 接口
│       ├── service/           # 业务编排
│       ├── engine/
│       │   ├── parser/        # Thread Dump 词法/语义解析、多 Dump 切分
│       │   ├── analyzer/      # 死锁、锁链根因、线程池、热点、对比、健康度分析
│       │   └── pattern/       # Dump 正则模式
│       ├── model/             # 领域模型
│       ├── dto/               # 传输对象
│       └── exception/         # 统一异常处理
├── threadscope-frontend/      # Vue 3 前端
│   ├── src/
│   │   ├── components/        # 业务组件 (仪表盘/线程/锁/热点/聚合/火焰图/对比)
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

### 解析

- **Thread Dump 解析** — 词法 + 语义两阶段解析，Virtual Threads 并行处理，支持最多 50,000 个线程
- **格式兼容** — HotSpot JVM (Java 8 ~ 24+) 经典与新格式线程头、SMR 段、构造器/lambda 等特殊方法名
- **多 Dump 自动切分** — 单个文件内含多段 `Full thread dump`（如 `kill -3` 输出日志）时自动按段切分，并识别 jstack 时间戳
- **多文件上传** — 一次拖入多个间隔抓取的 dump 文件，按文件名排序自动进入对比分析

### 分析引擎

- **死锁检测** — 三重策略：JVM 自报告段解析（支持多段切分）、synchronized 锁等待图三色 DFS 环检测、JUC 锁 (ReentrantLock/Semaphore) 保守图检测
- **锁等待链根因定位** — 沿 "等待者 → 持有者" 边做传递闭包，把几十条锁记录收敛成"元凶线程 X 拖住了 N 个线程"的结论
- **IO 型 RUNNABLE 细分** — 识别栈顶停在 native socket/文件读写的"假 RUNNABLE"线程，区分 CPU 瓶颈与下游 IO 瓶颈
- **已知病症指纹库** — 内置高频生产故障模式规则，命中即给出病名级诊断与处置建议：
  - 数据库连接池耗尽 (HikariCP / Druid / DBCP)
  - HTTP 客户端连接池耗尽 (Apache HttpClient 4/5)
  - 对象池耗尽 (Jedis / Commons-Pool)
  - 类加载锁竞争、日志 Appender 锁竞争
  - 疑似慢 SQL（线程卡在数据库响应读取）、同步 DNS 解析阻塞
- **健康度报告** — 十余项专家规则综合评估：死锁、阻塞风暴、BLOCKED 比例、线程池饱和、CPU 死循环嫌疑、IO 瓶颈、线程总量异常、非 daemon 线程审计、线程频繁创建 (churn)、Finalizer 积压、异常深栈（递归风险）
- **线程池洞察** — 识别常见线程池并统计忙碌/空闲线程比例
- **方法热点分析** — 聚合栈帧，定位高频调用方法
- **堆栈聚合** — 相同调用栈的线程自动分组，智能生成可读的组标签

### 多 Dump 差分对比

单个 dump 是瞬间快照，无法区分"正好在等"和"一直卡着"。上传多份间隔抓取的 dump 后自动进行：

- **栈不动检测** — 同一线程在所有 dump 中栈指纹完全一致（排除池内正常空闲），即真挂起/死循环/长事务
- **CPU 差值榜** — `cpu=` 是累计值，只有 dump 之间的差值才反映真实 CPU 消耗
- **线程数趋势** — 按名称前缀聚合的数量变化，直接暴露线程泄漏

### 可视化

- **火焰图** — 所有线程堆栈自底向上合并成调用树，宽度 = 经过该调用点的线程数，支持按状态过滤与点击缩放
- **top -H CPU 关联** — 粘贴 `top -H -p <pid>` 输出，OS 线程 PID 自动映射回 Java 线程 nid，直接看到最热线程在跑什么代码
- **仪表盘** — 状态分布环形图、TOP 10 列表（线程/线程池/方法/锁/CPU）、风险卡片

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

### 运行测试

```bash
cd threadscope-backend
./gradlew test
```

## 使用建议

获得高质量诊断的抓取方式：

```bash
# 间隔 5~10 秒抓取 3 份 dump（时间戳会被自动识别）
for i in 1 2 3; do jstack <pid> > dump-$i.txt; sleep 5; done

# 同时抓取线程级 CPU 快照，用于仪表盘的 CPU (top -H) 关联
top -H -b -n 1 -p <pid> > top-h.txt
```

把 `dump-1.txt dump-2.txt dump-3.txt` 一起拖入上传区，即可解锁 **Comparison** 差分视图；
在 Overview 的 **CPU (top -H)** 标签页粘贴 `top-h.txt` 内容可定位 CPU 热点线程。

## 主要 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/dump/upload` | 上传一个或多个 dump 文件（multipart，字段名 `file` 可重复） |
| POST | `/api/v1/dump/paste` | 粘贴文本分析 |
| GET | `/api/v1/analysis/{id}/overview` | 概览（含 `dumpCount`） |
| GET | `/api/v1/analysis/{id}/threads` | 线程列表（筛选/搜索/分页） |
| GET | `/api/v1/analysis/{id}/locks` | 锁与死锁 |
| GET | `/api/v1/analysis/{id}/thread-pools` | 线程池 |
| GET | `/api/v1/analysis/{id}/stack-aggregations` | 堆栈聚合 |
| GET | `/api/v1/analysis/{id}/method-hotspots` | 方法热点 |
| GET | `/api/v1/analysis/{id}/comparison` | 多 dump 对比结果（单 dump 返回 204） |
| GET | `/api/v1/analysis/{id}/calltree?state=` | 调用树（火焰图数据源） |
| POST | `/api/v1/analysis/{id}/cpu-correlation` | top -H 输出关联 |

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

## Roadmap

- IBM J9 / OpenJ9 dump 格式兼容
- `jcmd Thread.dump_to_file` JSON 格式（含虚拟线程）解析
- 分析报告导出与分享链接

## License

MIT
