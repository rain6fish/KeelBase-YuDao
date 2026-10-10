# 部署：demo2 实例

> 英文：[README.md](README.md)

本目录保存的是在 `demo2.keelbase.com.cn` 上应答的那个 KeelBase-YuDao 实例的部署。它就是 demo 机器上
**实际在跑**的那套，放在这里是为了**让机器能从仓库重建，而不是从记忆重建**。

## 先决条件

本栈**不是自足的**。它加入一个既有网络，并用那台机器的数据存储：

- 同一台机器上跑着一套 KeelBase 栈（compose 项目名 `keelbase`）⇒ 网络 `keelbase_default` 存在，且上面
  有 `keelbase-postgres-1`（`pgvector/pgvector:pg17` 容器）与 `keelbase-redis-1`。
- 外面有一个 nginx（`keelbase-web-1`）为 `demo2.keelbase.com.cn` 终止 TLS、把整个域名转给 `yudao-admin`。
  那个 server 块在**主仓**（`KeelBase/nginx.https.conf`）里、不在本目录；找 `server_name demo2.keelbase.com.cn`。

## 要产出的两份产物

两份构建产物放在本目录旁边一起部署；两者都**不受版本控制**（见 `.gitignore`）。

**服务 jar**。在有工具链的机器上从本仓构建——**宿主机器没有 JDK、也没有 Maven**。

```bash
# 在仓库根；scripts/jdk25.sh 解析 JDK 25 而不动你的默认 JDK
bash scripts/jdk25.sh mvn -q -DskipTests package -pl yudao-server -am
# ⇒ yudao-server/target/yudao-server.jar   （胖 jar，约 300 MB）
```

> ⚠ **正在跑**的实例会锁住这个 jar ⇒ `mvn package` 的 repackage 会失败，且**可能把胖 jar 覆盖成瘦 jar**。
> 重建前先停掉那个实例，或在一个独立 worktree 里构建。

**后台界面**不在本仓。`scripts/build-host-ui.sh` 会去取上游那棵树、构建它，再把 KeelBase 视觉层铺到
构建产物上。它还会把页面的接口地址钉成**同源**：上游自己的 `.env.prod` 里写着
`VITE_BASE_URL=http://localhost:48080`——那对**构建它的机器**是对的、对**任何服务它的机器**都是错的：
部署出去的页面会去问**访问者自己的机器**要接口，于是页面看着正常、每次调用却以网络错误收场。

```bash
bash scripts/build-host-ui.sh
# ⇒ target/yudao-ui-admin-vue3/dist-prod/
```

## 部署

在宿主机器上、将来存放本栈的目录里（demo 机器上是 `/opt/keelbase-yudao`）：

```bash
mkdir -p /opt/keelbase-yudao/ui
# 从本目录拷入：docker-compose.yml、admin-nginx.conf
# 拷入：yudao-server.jar  → 该目录
# 拷入：dist-prod/*       → ui/

cp .env.example .env        # 再填两个值——各自从哪来见 .env.example

bash bootstrap-db.sh        # 建库并灌 schema（幂等；RESET=1 重灌）

docker compose up -d
```

服务要一两分钟才起来，日志结尾是 `项目启动成功！`。这样检查：

```bash
docker logs -f yudao-server
curl -s -o /dev/null -w '%{http_code}\n' https://demo2.keelbase.com.cn/
curl -s https://demo2.keelbase.com.cn/ | grep -o '<title>[^<]*</title>'   # ⇒ <title>KeelBase</title>
```

## AI 模型回路，以及去哪儿用它

KeelBase 自己的面挂在服务**根路径**上、不在 `/admin-api` 之下——AI 回路就在那儿应答。后台 nginx 把
三个前缀转过去（`/app`、`/ai`、`/audit`）；当 `.env` 里 `AI_ENABLED=true` 且有 `MODEL_API_KEY` 时，
由**真模型**驱动它们：

```
POST https://demo2.keelbase.com.cn/ai/chat    →  { "provider": "spring-ai", "toolCalls": [...] }
POST https://demo2.keelbase.com.cn/ai/task    →  步数 ≥ 2，且**要写**的那一步回来时被扣下等人、带着 token
GET  https://demo2.keelbase.com.cn/app/capabilities
```

提供方默认是 DeepSeek——`MODEL_BASE_URL=https://api.deepseek.com`、`MODEL_NAME=deepseek-v4-flash`——
而 `MODEL_BASE_URL` 可指向任何兼容 OpenAI 协议的端点。两个 `POST` 都用宿主自己的访问令牌（运行时把它
解析成一个宿主用户），所以只有**一套身份**、不是两套。运行时还在根路径注册了 `/auth`、`/customers`、
`/follow_ups`；这三个**有意不转发**——见 `admin-nginx.conf`。

## 改这套东西之前值得知道的

- **密钥不在这里。** `docker-compose.yml` 从 `.env` 读 `PG_PASSWORD` 与 `AUDIT_KEY`，而 `.env` 已被 git
  忽略。数据库口令是宿主既有的 Postgres 角色的，从运行中的容器上读出；审计密钥必须**生成一次之后不再动**，
  因为审计链要靠它重算哈希来验证。
- **数据存储是共用的。** 库 `ruoyi-vue-pro` 住在宿主另一套栈的 Postgres 容器里，Redis 也是那套栈的、
  用的是**第 1 号数据库**以免与其缓存相撞。这里**不为二者建任何容器**。
- **在 demo 机器上，卡住的是内存而不是磁盘。** 服务以 `-Xmx384m` 跑，常驻仍约 600 MB；机器总共 1.6 GB，
  所以它在换页。若这个实例要靠得住，应当**给机器加内存**，而不是继续把堆调小。
- **AI 模型回路**由 `.env` 里的 `AI_ENABLED` 加 `MODEL_API_KEY` 控制。两者都给上，KeelBase 的
  `/ai/chat` 与 `/ai/task` 就由**真模型**驱动；base URL 与模型名默认是 DeepSeek 的
  （`https://api.deepseek.com`、`deepseek-v4-flash`），可指向任何兼容 OpenAI 协议的提供方。
  `AI_ENABLED` 不设时它们由确定性 planner 作答，`/ai/chat` 报 `provider: deterministic`。
- `--spring.datasource.dynamic.datasource.master.type=...HikariDataSource` 那个参数不是可有可无：
  在 PostgreSQL 上，上游默认的连接池会让**约一半的登录**失败。
