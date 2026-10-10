# KeelBase 宿主 — 本线的构建与运行

> 英文：[KEELBASE-DEV.md](KEELBASE-DEV.md)

本文件是本仓的**开发说明**：需要什么工具链、怎么构建、怎么对着数据存储跑起来。本线是什么，见
[KEELBASE-HOST.zh-CN.md](KEELBASE-HOST.zh-CN.md)。

## 工具链 —— **同时**要两个 JDK，这是故意的

这里的平台编译目标是 **Java 25**。本仓嵌入的运行时 `keelbase4j-core` 目标是 **Java 17**。两者必须
同时可用，而本机默认的 JDK 是 17。

`scripts/jdk25.sh` 为**一条命令**解析出一个 JDK 25，**不动** `JAVA_HOME` 与 `PATH`。后半句不是客气：
本机有多个会话共用，挪动默认 JDK 会改变**别人**构建所用的东西。

```bash
bash scripts/jdk25.sh                     # 报告解析到的 JDK，什么都不改
bash scripts/jdk25.sh java -version       # 用 25 跑一条命令
```

解析顺序，**先成的胜出**：`$KEELBASE_JDK25` → `$JDK25_HOME` → `/d/Develop/jdk-25` → 从 Adoptium API
下载到 `${KEELBASE_JDK25_DIR:-/d/Develop}`。**前三处只要有一处成立，就不装任何东西。**

**为什么不干脆装成系统级**：核心自己的规矩是它的产物基线 Java 17。本宿主是那个例外，所以例外被**限制在
需要它的那些命令里**。

## 构建

```bash
bash scripts/jdk25.sh mvn -pl yudao-server -am -DskipTests -Dmaven.test.skip=true package
```

`-pl yudao-server -am` 构建服务端及其依赖，跳过那十五个没接进服务端的业务模块。产物是
`yudao-server/target/` 下的 fat jar。

## 运行

`scripts/run-server.sh` 一条命令就是全部，上面的验证就是用它跑的：

```bash
bash scripts/run-server.sh
```

数据存储与运行时自己的设置一律走**命令行参数**，**不写进**上游的 `application-local.yaml`。两个理由：
上游那个文件保持一字未动；同一份检出可以指向另一套栈而不必改任何东西。

| 参数 | 为什么需要 |
|---|---|
| `--spring.datasource.dynamic.datasource.master.url` / `.username` / `.password` | 平台的数据存储 |
| `--spring.data.redis.host` / `.port` | 平台的缓存 |
| `--spring.flyway.baseline-on-migrate=true` `--spring.flyway.baseline-version=0` | 平台的 schema 是手工灌的，故核心的 Flyway 需要基线——而版本**必须是 `0`**，否则核心的 **V1 被跳过**、一张表**安静地**消失 |
| `--keelbase.audit.hmac-key=…` | 运行时的审计链；嵌入时它没有自己的 `application.yml`，缺这项**起不来** |
| `--keelbase.wire.exclude-packages=cn.iocoder.yudao` | 让运行时的响应信封**不套在**本宿主自己的控制器上 |
| `--yudao.access-log.enable=true` | 宿主的 profile 里这一项是**关着的**，于是接口访问日志表是空的——而面板上「接口访问」那格正是从那表读的，故它一直停在 0。命令行参数优先于 profile 的 yaml，所以这能在**不编辑上游**的前提下把它打开。代价是**每个请求都会写一行**，这也是它做成参数、而不写死的原因。置 `ACCESS_LOG=false` 可恢复 |
| `--yudao.tenant.ignore-tables=…` | 点名**由 KeelBase 治理**的表，好让宿主的租户拦截器**不再**去收束它们（一行的权威只有一个——见本线的决定索引） |
| `--spring.datasource.dynamic.druid.validation-query="SELECT 1"` | 宿主自己的值是 `SELECT 1 FROM DUAL`，那是 **MySQL**——PostgreSQL 没有 `DUAL` |
| `--spring.datasource.dynamic.datasource.master.type=com.zaxxer.hikari.HikariDataSource` | **换掉连接池**。见下——用 Druid 时约一半的登录会失败 |

## 池被换掉了，以及为什么

在 PostgreSQL + **Druid** 上，约一半的登录返回 500：

```
org.postgresql.util.PSQLException: 这个 statement 已经被关闭
  at org.postgresql.jdbc.PgStatement.checkClosed
  …  DruidPooledPreparedStatement.getMaxRows
  …  MybatisMapperMethod → AdminUserMapper.selectWithCursor
```

**换成 Hikari 就一次都不发生**——连打十二次登录、以及门禁转绿。改动就是上面那条 `…master.type` 参数，而且它是**命令行参数**：**没有一个上游文件为它而被改**。

走到那里绕了一段**值得留着**的弯路。第一轮得出的结论是「**Druid 的预备语句缓存已排除**」，并把它当成定论报了出去。**那是假绿灯**：验证过的是「**属性会绑定**」、**不是**「**池真的变了**」。去读池自己的状态（Druid 的 `/druid/datasource.json`）才看到，它一直是 `PoolPreparedStatements = true`。而随后是那条**更有用**的发现：

> **这个 Druid 池**无法从应用外面配置**。**`spring.datasource.dynamic.druid.pool-prepared-statements=false`——**命令行参数**也好、**优先级更高的外部配置文件**也好——都改不动它；姊妹前缀 `spring.datasource.druid.*` 也不行；**连改 `application-local.yaml` 再重新打包**也不行——那次之后**核对过 jar 里确实是 `false`，而池仍报 `true`**。

⇒ **「拿属性去调 Druid」这一整类修法在这里是死路**，下一件该试的**不是另一个属性**。JDBC URL 上的 `prepareThreshold=0` 也试过，同样没用。

**门禁该干的事，门禁随后就干了**：对着修好后的栈**第一次跑**，它在**生成模块**里抓到**两处方言错配**——一是 `deleted` 用 `boolean`，而宿主的逻辑删除拿 **`0`/`1`** 去比（它自己的 schema 写的是 `deleted int2 not null default 0`）；二是 `id` 后面**没有序列**（宿主的惯例是 `id int8 not null` 加一个 `<table>_seq`，而它生成的 DO 带着 `@KeySequence`）。**两处都已在 Spec 适配器里修掉**，而它们**从 Spike 起就一直躺在那份模块里**。

上面那条 `--spring.datasource.dynamic.druid.validation-query="SELECT 1"` **保留**，因为 PostgreSQL 确实没有 `DUAL`；它**不是**这次修法的一部分，也**没有**声称是。

**⚠ 两个坑，都是在本宿主上量出来的**——跑的过程见 Spike 记录：

- 中文经 `docker exec -i` 管进 `mysql` 客户端会变坏，除非加 `--default-character-set=utf8mb4`。
  它**曾经落进生成代码的注释里**。
- 中文经本 shell 作为**命令行参数**传给 `curl` 会坏；改用 `--data-binary @文件`。

## 数据存储

本线的数据存储是 **PostgreSQL + pgvector，加 Redis**，在 `scripts/docker-compose.datastore.yml`。
上游自带一份跑 MySQL 的 compose；那个文件是上游的、**一字不动**——这份是我们的，也是本线实际对着跑的。

```bash
bash scripts/bootstrap-datastore.sh          # 库不在就建，空就灌
RESET=1 bash scripts/bootstrap-datastore.sh  # 删库重灌
```

**已端到端验证（2026-10-08）。** 栈起得来、扩展报 `vector 0.8.7`、上游那份转换 schema 灌成 **49 张表**，
服务对着它起得来：Flyway 应用了运行时的四条迁移、`/auth/me` 作答、一次对话路由到工具并**从 PostgreSQL 里
读出一行真数据**、审计链对自己的行校验通过。

**第一次跑发现的一件事，已在 `yudao-server/pom.xml` 修掉：** 上游是按 MySQL 构建的、**不管理任何 PG 驱动**，
故在没有它之前平台启动即报 `ClassNotFoundException: org.postgresql.Driver`。该依赖现已声明在那里。

读失败信息之前有一件关于 schema 的事值得先知道：`sql/postgresql/ruoyi-vue-pro.sql` 是**从 MySQL 机器转换**来的
——它自己的文件头写着，还带着 `dual` 这样的残留。**它在这里是干净导入的**；若那里出错，那是**那份转换文件**的
事实、不是本线的。

**⚠ 未覆盖：** pgvector 在位，是运行时知识层走**语义路径**的前提；但那条路还要一个嵌入模型，而这里没配
——故**语义路径本身没有跑过**，只验了扩展在位。

## 从 Spec 生成一个受治理的模块

```bash
bash scripts/gen-from-spec.sh specs/products.json           # 写
bash scripts/gen-from-spec.sh specs/products.json --check   # 比对，有漂移即非零
```

给一份 Business Spec，它产出三个文件，**都是本项目自己的**、不是宿主生成器的：

| 产出 | 是什么 |
|---|---|
| `sql/keelbase-<module>.sql` | 建表的 DDL，带治理列与宿主的基列。**MySQL**，中文标签骑在 `COMMENT` 里——这个宿主自己的生成器在导入时读它，于是命名那一趟就并进了 DDL |
| `…/keelbase/generated/<Entity>Tools.java` | 模块的 AI 工具面：一读一写，各配路由 |
| `…/keelbase/generated/<Entity>Governance.java` | 模块的行范围，作为**切在模块自己 service 上的切点** |

**它不做**的是生成模块自己的 CRUD。那是这个宿主的代码生成器、经它的 API 驱动——**另一趟**，因为它需要一套跑着的栈；也正是因此，这一半是**确定性的、离线的**。

**`--check` 证明了什么、没证明什么。** 它证明盘上的文件**就是 spec 所说的那样**；它**不**证明它们**是对的**——它比的是**上一次运行写下的**，所以模板里的 bug 会**忠实地重新生成自己**、并且照报 `no drift`。第一版 DDL 模板漏了行尾逗号、还把 `comment` 粘到长约束上，产出了**无效 SQL** 而 `--check` 说它干净；逮住它的是**与 Spike 那份手工文件对账**。

**边界在哪，以及它是被检查的。** 适配器拥有**模块级**面——表 · 工具面 · 行范围切点——而宿主级件由宿主线**手写一次**。在上面那两个命名空间之内，这一点现在是**被强制**的、不是被描述的：

```bash
bash scripts/gen-from-spec.sh --check-boundary
```

它**两个方向都报错**：生成的缺了 ⇒ Spec 不再是源；两边都不属于 ⇒ 出现了**第二个源**。这道检查值得有，因为命名空间**并不靠名字区分两者**——`sql/keelbase-products.sql` 是生成的、`sql/keelbase-crm.sql` 是手写的，并排放在一起。因此新增一个手写文件必须**在 `SpecCodegen.HAND_WRITTEN` 里声明**；那是要点，不是麻烦。

## 上游，以及怎么对着它比

`upstream` 指向 `https://github.com/YunaiV/ruoyi-vue-pro.git`——**规范源**；项目的 Gitee 镜像这里**没配**。
它存在，是因为**本仓的历史是全新起算**的，而这意味着通常的 fork 工作流**用不了**：

> **`git merge upstream/master-jdk25` 与 `git rebase` 会被拒绝**——两条历史**无共同祖先**
> （`refusing to merge unrelated histories`）。这个 remote 的用途是 **diff / 打补丁 / 取文件**，
> **不是合并**、也**不是推送**：它的 **push URL 已被刻意设成哨兵**（`DISABLED-READ-ONLY`），
> 好让手滑**推不到上游**去。

```bash
git fetch --depth=1 upstream master-jdk25     # 刻意保持浅；要更深用 --deepen
git diff upstream/master-jdk25 HEAD --stat
```

**读 diff 之前有两件事值得知道，都是踩出来的：**

1. **这里的 fetch 要用 HTTP/1.1。** 直接 `git fetch upstream` 会以
   `Failed to connect to github.com port 443` 失败，**而同一时刻** `git ls-remote upstream` 与
   `curl https://github.com` **都是通的**。用
   `git -c http.version=HTTP/1.1 fetch --depth=1 upstream master-jdk25` 才成。
2. **只有模式变了，`git diff` 也会报成 modified。** 约 **130 个文件**与上游只差**可执行位**——
   上游记的是 `100755`，这份拷贝在**经 Windows 过来时丢了那个位**。要看**内容**改动，比 blob：

   ```bash
   git diff --name-only upstream/master-jdk25 HEAD | while read -r p; do
     [ "$(git rev-parse "HEAD:$p")" != "$(git rev-parse "upstream/master-jdk25:$p")" ] && echo "$p"
   done
   ```

## 本线的记录在哪

| | |
|---|---|
| 运行时的这一侧（本宿主） | 本目录（`KEELBASE-HOST.zh-CN.md`） |
| 本线的决定与待办 | 项目的**私有仓** `KeelBase-YuDao/`——**不在本仓** |
| 起始本线的那次 Spike | 同上，`KeelBase-RuoYi/Yudao真跑型Spike_2026-10-08.md` + `evidence-yudao-spike-2026-10-08/` |
