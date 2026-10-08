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

数据存储与运行时自己的设置一律走**命令行参数**，**不写进**上游的 `application-local.yaml`。两个理由：
上游那个文件保持一字未动；同一份检出可以指向另一套栈而不必改任何东西。

| 参数 | 为什么需要 |
|---|---|
| `--spring.datasource.dynamic.datasource.master.url` / `.username` / `.password` | 平台的数据存储 |
| `--spring.data.redis.host` / `.port` | 平台的缓存 |
| `--spring.flyway.baseline-on-migrate=true` `--spring.flyway.baseline-version=0` | 平台的 schema 是手工灌的，故核心的 Flyway 需要基线——而版本**必须是 `0`**，否则核心的 **V1 被跳过**、一张表**安静地**消失 |
| `--keelbase.audit.hmac-key=…` | 运行时的审计链；嵌入时它没有自己的 `application.yml`，缺这项**起不来** |
| `--keelbase.wire.exclude-packages=cn.iocoder.yudao` | 让运行时的响应信封**不套在**本宿主自己的控制器上 |
| `--yudao.tenant.ignore-tables=…` | 点名**由 KeelBase 治理**的表，好让宿主的租户拦截器**不再**去收束它们（一行的权威只有一个——见本线的决定索引） |

**⚠ 两个坑，都是在本宿主上量出来的**——跑的过程见 Spike 记录：

- 中文经 `docker exec -i` 管进 `mysql` 客户端会变坏，除非加 `--default-character-set=utf8mb4`。
  它**曾经落进生成代码的注释里**。
- 中文经本 shell 作为**命令行参数**传给 `curl` 会坏；改用 `--data-binary @文件`。

## 本线的记录在哪

| | |
|---|---|
| 运行时的这一侧（本宿主） | 本目录（`KEELBASE-HOST.zh-CN.md`） |
| 本线的决定与待办 | 项目的**私有仓** `KeelBase-YuDao/`——**不在本仓** |
| 起始本线的那次 Spike | 同上，`KeelBase-RuoYi/Yudao真跑型Spike_2026-10-08.md` + `evidence-yudao-spike-2026-10-08/` |
