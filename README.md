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
- **格式兼容** — HotSpot JVM (Java 8 ~ 24+) 经典与新格式线程头、SMR 段、构造器/lambda 等特殊方法名；
  `jcmd <pid> Thread.dump_to_file` 的 JSON 与纯文本格式 (含全部虚拟线程)
- **编码自动识别** — UTF-8 / UTF-16 (含 Windows PowerShell 重定向产生的带 BOM 文件) / GBK，也可通过 `charset` 参数指定
- **多 Dump 自动切分** — 单个文件内含多段 `Full thread dump`（如 `kill -3` 输出日志）时自动按段切分，并识别 jstack 时间戳
- **多文件上传** — 一次拖入多个间隔抓取的 dump 文件，按文件名排序自动进入对比分析；每份快照都做完整分析，可在顶栏切换
- **线程身份** — 以 Java 线程 ID (`#N`) 作为唯一标识，重名线程不会被合并；跨快照配对时识别 JVM 重启
- **数据质量提示** — 未用 `jstack -l`、缺少 `cpu=` 字段、只有单份快照、JVM 重启、状态为推断等情况会在报告顶部说明影响与补抓方式

### 分析引擎

- **死锁检测** — 三重策略：JVM 自报告段解析（支持多段切分）、synchronized 锁等待图三色 DFS 环检测、JUC 锁 (ReentrantLock/Semaphore) 保守图检测
- **锁等待链根因定位** — 沿 "等待者 → 持有者" 边做传递闭包，把几十条锁记录收敛成"元凶线程 X 拖住了 N 个线程"的结论
- **IO 型 RUNNABLE 细分** — 识别栈顶停在 native socket/文件读写的"假 RUNNABLE"线程，区分 CPU 瓶颈与下游 IO 瓶颈
- **已知病症指纹库** — 内置高频生产故障模式规则，命中即给出病名级诊断与处置建议：
  - 数据库连接池耗尽 (HikariCP / Druid / DBCP)、Redis 同步等待 (Lettuce / Redisson)
  - HTTP 客户端连接池耗尽 (Apache HttpClient 4/5)
  - 对象池耗尽 (Jedis / Commons-Pool)
  - 类加载锁竞争、日志 Appender 锁竞争、异步日志队列写满 (Log4j2 RingBuffer / Logback AsyncAppender)
  - 疑似慢 SQL（线程卡在数据库响应读取）、同步 DNS 解析阻塞、SecureRandom 熵源阻塞
  - 正则灾难性回溯、HashMap 并发死循环、异常风暴 (大量线程在 fillInStackTrace)
  - 公共 ForkJoinPool 饥饿、无超时等待 Future、Spring 单例创建锁竞争
- **虚拟线程分析** — 识别载体线程 (`Carrying virtual thread #N`)、虚拟线程按容器统计、pinning 检测 (synchronized 内阻塞 / native 帧)，并区分 JDK 21~23 与 JDK 24+ (JEP 491) 的行为
- **健康度报告** — 十余项专家规则综合评估：死锁、阻塞风暴、BLOCKED 比例、线程池饱和、CPU 热点 (含 JDK 栈顶)、IO 瓶颈、线程总量异常、非 daemon 线程审计、线程频繁创建 (churn)、Finalizer 积压、异常深栈（递归风险）、未命名线程池；阈值可配置
- **线程池洞察** — 识别 Tomcat / Jetty / Undertow / Vert.x / Spring @Async / Quartz / XXL-Job / Dubbo / gRPC / Hystrix / Kafka / RabbitMQ / RocketMQ / Lettuce / Redisson 等线程池；按栈判定空闲与忙碌 (在任务中等连接池、Future 的 WAITING 线程计为忙碌)；对 `pool-N-thread-M` 等未命名线程池给出命名治理建议
- **方法热点分析** — 聚合栈帧，定位高频调用方法
- **堆栈聚合** — 相同调用栈的线程自动分组，智能生成可读的组标签

### 多 Dump 差分对比

单个 dump 是瞬间快照，无法区分"正好在等"和"一直卡着"。上传多份间隔抓取的 dump 后自动进行：

- **栈不动检测** — 同一线程在所有 dump 中栈指纹完全一致（排除池内正常空闲），即真挂起/死循环/长事务
- **CPU 差值榜** — `cpu=` 是累计值，只有 dump 之间的差值才反映真实 CPU 消耗
- **线程数趋势** — 按名称前缀聚合的数量变化，直接暴露线程泄漏

### 可视化

- **锁等待关系图** — "等待者 → 锁 → 持有者"分层布局，根因线程居右，同一等待位置的大量线程折叠为分组节点；死锁环高亮，单击高亮整条等待链
- **代码归属** — 按应用配置业务包前缀，栈帧分为 JDK / 三方框架 / 业务代码三层：业务帧高亮、框架帧可折叠；配置源码仓库模板 (GitLab / GitHub / IDE) 后业务帧可直接跳转源码
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

# JDK 21+ 使用虚拟线程时，导出全部虚拟线程 (jstack 只能看到正在挂载的)
jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json
```

把 `dump-1.txt dump-2.txt dump-3.txt` 一起拖入上传区，即可解锁 **Comparison** 差分视图；
在 Overview 的 **CPU (top -H)** 标签页粘贴 `top-h.txt` 内容可定位 CPU 热点线程。

## 主要 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/dump/upload` | 上传一个或多个 dump 文件（multipart，字段名 `file` 可重复；可选 `charset`、`businessPackages`、`applicationName`） |
| POST | `/api/v1/dump/paste` | 粘贴文本分析（可选 `businessPackages`、`applicationName`） |
| GET | `/api/v1/analysis/{id}/overview` | 概览（含快照列表、数据质量提示、虚拟线程报告、业务包推荐） |
| GET | `/api/v1/analysis/{id}/threads` | 线程列表（筛选/搜索/分页；`kind=PLATFORM,VIRTUAL,CARRIER`） |
| GET | `/api/v1/analysis/{id}/thread?ref=` | 按线程标识 (`#N`) 或线程名查询单个线程 |
| GET | `/api/v1/analysis/{id}/locks` | 锁与死锁 |
| GET | `/api/v1/analysis/{id}/lock-graph` | 锁等待关系图（节点/边；`includeConditionWaits=true` 显示无持有者的条件等待） |
| GET | `/api/v1/config/code-layers` | JDK / 框架包前缀列表与默认业务包 |
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

多份快照通过 ID 寻址：`{id}` 为最新一份，`{id}~s0`、`{id}~s1` … 为第 N 份，所有分析接口通用。

## 配置

`application.yml` 中 `threadscope.analysis` 下：

| 配置项 | 说明 |
|---|---|
| `business-packages` | 默认业务包前缀（上传时未指定时使用；为空则 JDK 与已知框架之外的代码按业务代码推断） |
| `health.*` | 健康检查阈值，如 `thread-count-warning`、`pool-exhaustion-ratio`、`block-storm-threshold`，按应用规模调整 |

## Roadmap

- IBM J9 / OpenJ9 dump 格式兼容
- 分析报告导出与分享链接

## License

MIT
