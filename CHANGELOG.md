# Changelog / 更新日志

This file records the releases of **this repository's own version line**. Upstream's release history is
not carried — see [README](README.md) and [NOTICE](NOTICE). A version number here describes *this
derivative*, not upstream: the line starts at `0.1.0` and is deliberately decoupled from upstream's
date-based tags (`v2026.0x(jdkX/Y)`), which remain history and are never reused as release numbers.

本文件记录**本仓自家版本线**的发布。上游的发布历史不随仓携带 —— 见 [README](README.md) 与
[NOTICE](NOTICE)。这里的版本号描述的是**本衍生仓**，不是上游：版本线从 `0.1.0` 起，刻意与上游的日期式
tag（`v2026.0x(jdkX/Y)`）解耦；后者保留为历史，绝不在这里复用为发布号。

---

## v0.1.1 — 2026-10-18

**The host pins a released runtime, and CI can resolve it. / 宿主钉住已发布的运行时，CI 解析得到。**

Highlights

* The host pins the **released** `keelbase4j-core` / `keelbase4j-springai` **0.1.9** instead of an unreleased `0.1.8-SNAPSHOT`, so a clean machine and CI resolve it from Maven Central rather than from one developer's `~/.m2`.
* The conformance CI job no longer builds the runtime from a commit SHA: with a released coordinate there is nothing to build, which removes the artifact-missing failure that job hit on every run.

Also

* The `generator` CI job resolves the same released coordinate, so the committed generated files are checked on a runner without a local runtime install.
* No wire change, no behaviour change; the host build was verified locally against 0.1.9.

Full details: [CHANGELOG.md](https://github.com/rain6fish/KeelBase-YuDao/blob/v0.1.1/CHANGELOG.md)

亮点

* 宿主改为钉住**已发布**的 `keelbase4j-core` / `keelbase4j-springai` **0.1.9**，不再钉未发布的 `0.1.8-SNAPSHOT`；干净机器与 CI 从 Maven Central 解析它，而不是从某个开发者的 `~/.m2`。
* 符合性 CI job 不再按提交 SHA 构建运行时：坐标已发布，就没有要构建的东西，这也移除了那次 job 每次都撞上的「缺件」失败。

其它

* `generator` CI job 解析同一个已发布坐标，故提交的生成物能在没有本地运行时安装的 runner 上被检查。
* 线缆不变、行为不变；宿主构建已在本地对 0.1.9 验过。

完整详情：[CHANGELOG.md](https://gitee.com/rain6fish/KeelBase-YuDao/blob/v0.1.1/CHANGELOG.md)

---

## v0.1.0 — 2026-10-18

**The first own version line: the host is YuDao, the governance is KeelBase. / 第一条自家版本线：宿主是芋道，治理是 KeelBase。**

Highlights

* **The KeelBase runtime runs inside YuDao as the host, not beside it** — identity bridge, governed tool calls, confirmation, decision trace, audit and revoke, applied at the seam the host actually calls.
* **The host adapter decisions are recorded and enforced** (D25/D26): the host is one adapter; the KeelBase cut is the single data-scope authority; the host's AI module is the carrier while governance stays with KeelBase.
* **An execution id links one attempt's three rows** — intent, result and side effect — and a side-effect row carries the caller it was for.

Also

* A read-only audit reader reaches the governance read faces through the capability seam.
* A conformance suite with four governance rates runs in CI against PostgreSQL and Redis.
* The version line starts here at `0.1.0`, decoupled from upstream's date-based tags; the upstream pin and history stay as history.

Full details: [CHANGELOG.md](https://github.com/rain6fish/KeelBase-YuDao/blob/v0.1.0/CHANGELOG.md)

亮点

* **KeelBase 运行时跑在芋道**宿主**之内**，而不是旁边 —— 身份桥、受治理的工具调用、确认、决策轨迹、审计与撤销，施加在宿主实际调用的那道缝上。
* **宿主适配器的裁定已记录并施加**（D25/D26）：宿主是一个适配器；KeelBase 切点是唯一数据范围权威；宿主的 AI 模块作载体，治理始终由 KeelBase 施加。
* **一个 execution id 串起一次尝试的三行** —— intent、结果与副作用 —— 且副作用行说得出发起人。

其它

* 只读审计读者经能力缝到达治理读面。
* 符合性套件与四个治理率在 CI 中对 PostgreSQL 与 Redis 运行。
* 版本线自此以 `0.1.0` 起，与上游日期式 tag 解耦；上游的 pin 与历史保留为历史。

完整详情：[CHANGELOG.md](https://gitee.com/rain6fish/KeelBase-YuDao/blob/v0.1.0/CHANGELOG.md)
