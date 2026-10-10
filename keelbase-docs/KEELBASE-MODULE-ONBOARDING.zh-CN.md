# KeelBase-YuDao —— 接入一个业务模块

> English: [KEELBASE-MODULE-ONBOARDING.md](KEELBASE-MODULE-ONBOARDING.md)

把一个业务模块纳入 KeelBase，分两半。一半**每个宿主只写一次**，之后的模块白得：身份、规划器、审计链、确认引擎。
另一半**每个模块都要写**。本文件就是后一半，写成一份清单。

它**不裁任何东西**。哪儿缺决定，它就点出是哪一条，而不是替你选。

**写于 2026-10-10**，材料是现有的两个模块：`crm`（上游自己的代码——283 个 Java 文件，我们没写、没重新生成、没改）
与 `products`（由 `specs/products.json` 生成）。两个样本是**跨 Host 共性证据**里那条同一个上限：够看见一个形状重复，
不够冻结一份契约。

## 1. 哪一半是哪一半

| 那一半 | 住在哪 | 新模块要动它吗 |
|---|---|---|
| 身份桥 | `YudaoCallerAuthenticator` · `YudaoIdentityResolver` · `YudaoCallerId` · `ActingCaller` | 不用 |
| 规划器 | `YudaoToolPlanner` · `YudaoToolFaceConfiguration` | **不用** |
| 行范围机器 | `YudaoModuleScope` | 不用——但有 §4 那条前提 |
| 审计 · 确认 · 撤销 · 符合性 | 平台 `keelbase4j-core` | 不用 |
| **治理切面** | 模块自己的包 | **要** |
| **工具面** | 模块自己的包 | **要** |
| **路由** | 模块自己的包 | **要** |

规划器之所以只写一次，是因为它**从不点名任何工具**。`YudaoToolFaceConfiguration` 从上下文里取
`List<ModuleRoute>`，于是**声明一个路由 bean 就是一个模块对路由的全部贡献**——加模块时 `YudaoToolPlanner` 一个字不改。

## 2. 一个新模块的成本

三件东西，落点通常是**两个文件**：一个 `*Governance` 切面、一个 `*Tools` 配置（路由是后者里的 `@Bean`）。
`ListYudaoUsersTool` 配 `YudaoUsersRoute` 则是同样三件东西摊在两个文件里——那个拆法是风格选择，不是规矩。

### 2.1 治理切面 —— 切点**就是**清单

`@Aspect @Component`，切点点名模块**自己的 service**：

```java
@Around("execution(* cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService.getCustomerPage(..))")
```

**为什么在 service 上、不在工具里。** 收束写进 AI 工具，就只治理了工具自己那一次调用，模块自己的 REST 路径照旧敞开。
把通知点在模块的 service 上，REST 路径与工具才被**同一批通知**管住。生成模块的第一版犯的正是这个错，它的文件里记着。

**收窄什么——读，别套模板。** CRM 需要的那四条通知不是模板，是**读 CRM 自己的代码**找出来的四件事。能推广的是**要问任何模块的
那组问题**：

| 问 | CRM 的答案（在他们的代码里读到的） |
|---|---|
| 不给筛选时，分页返回什么？ | `sceneType` 为空时 `CrmSceneTypeEnum` **一个条件都不加**，而请求 VO 把 null 记作「全部」 |
| 按 id 读受范围约束吗？ | 不受——`getCustomer(Long)` 就是一句裸的 `customerMapper.selectById(id)` |
| 保存 VO 允许客户端指认别人为属主吗？ | 允许——属主列就在 VO 里 |
| 按 id 的更新 / 删除校验归属吗？ | 不校验 |

每个模块对这四问的答案都不同，有的会答「这里没有要堵的」。**那次阅读就是这份活本身，压缩不了。**

**把收窄声明成收窄。** CRM 那条通知只问属主这一问，而上游的 `crm_permission` 除负责人外**还给参与方** READ 与 WRITE。
所以那条通知**比模块更严、不等于模块**——它的文件这么写着；要放宽到权限表，是对**这个宿主词表**的决定，
**留而未决、不猜**。每个模块都欠同一句话。

### 2.2 工具面 —— `AiTool` bean

一个 `@Configuration` + `@Bean AiTool`：一读（R1）一写（R3）。**写**要声明 `parameters()`——一份写了工具不读的参数的提议，
会在闸口以 `invalid_arguments` 被拒，**到不了人**。

**把调用方带着。** 上游自己的分页查询把用户 id 作为**入参**，故身份必须**传递**、不只是「能拿到」：
`ActingCaller.as(principal.userId(), …)`，外加宿主的租户（`TenantUtils.execute(...)`）——宿主 service 调用没有租户会被拒。

**投影。** 输出时把身份字段与联系字段去掉；管理端那条红线是**身份字段在边缘掩码**。

### 2.3 路由

每个工具一条 `ModuleRoute`：`toolName()` · `triggers()` · `args(context)`。规划器先匹配触发词，
宿主没说的**一律回落到**运行时的 `RuleBasedPlanner`——**这条回落是承重的**：提供任何一个 `ToolCallPlanner`
都会让运行时自己的自动配置**让位**。

### 2.4 构建会来查你的两处

**手写的 SQL 要声明。** 上游不发布其 schema 的上游模块，会有一份我们推导的 `sql/keelbase-<module>.sql`。
把它加进 `keelbase-codegen/src/main/java/cn/iocoder/yudao/codegen/SpecCodegen.java` 的 `HAND_WRITTEN`，
否则 `bash scripts/gen-from-spec.sh --check-boundary` 会把它报成 `UNDECLARED` 并以 1 退出。

**手写的 Java 别放进 `generated/`。** 边界检查只管两个命名空间：`sql/keelbase-*.sql` 与 `keelbase/generated/*.java`。
我们手写的模块（CRM）把 `*Governance` 与 `*Tools` 留在**上一级**包 `cn.iocoder.yudao.server.keelbase`，
**不是** `…server.keelbase.generated`。放进 `generated/` 会把检查搞红——这是**被强制**的，不是命名偏好。

### 2.5 门禁

新模块配一个自己的场景脚本，抄 `scripts/run-crm-customer-scenarios.mjs` 的形状。它断言的是 `D28 ④` 所称的**第一道验收门**
四件事：能以**明确身份**访问 · 越权被拒 · 高风险写入被扣下等人 · 副作用可审计。
**每条断言都用本次唯一的名字收束，绝不按整张表计数**——这样门禁在**一个已经跑过的库上**、
**以及跟它自己的另一次运行并排**时都成立。

## 3. 生成那条路是同样三件东西，由机器产出

`specs/products.json` → `bash scripts/gen-from-spec.sh specs/products.json`，产出该模块的 DDL、它的 `*Tools`、它的 `*Governance`。
同样三个形状，是**产出**的、不是手打的。

这条路有一处更容易，方向值得写下来，因为它与通常方向相反：分页请求 VO 是**我们自己的**，故它本身就带属主列、
生成的 mapper 也已经把它变成 `eqIfPresent`。收束一次分页 = 调用前一句 `setOwnerUserId`——而且**总数一起收窄**，
没有哪一页的 total 与它的行不一致。

**这条路「不用手工重复」只对由我们生成的模块成立。** 对上游写的模块，没有 Spec 可写——见 §2。

## 4. 四件事决定「每模块一次」是贵还是便宜

1. **那台范围机器是「属主列」机器。** `YudaoModuleScope.belongsToCaller` 比的是 `ownerUserId`。行范围按**部门**、
   **权限表**或**租户**的模块，套不上它，要长出**第二台**机器。这就是「每次一个样」与「每次都一样」的分界线。
2. **没有任何东西能枚举「哪些模块被治理了」。** RuoYi 有 `GovernedModule` 接口与注册表；YuDao 的 `CrmCustomerGovernance`
   与 `ProductsGovernance` 只是裸 `@Component`。**没有一个地方列出被治理的模块**——于是也没地方能读出「有个模块被漏了」。
3. **每个模块欠一次收窄决定**（§2.1）：只认属主，还是对齐模块自己的权限表。
4. **抽象边界尚未定。** `YD-T7` 开着，而跨 Host 共性证据把 `ActingCaller` · `ModuleRoute` · 「路由+回落」式规划器列为
   **上提候选**。现在写的接缝，将来包位置可能挪——因为样本仍然只有两个宿主。

## 5. 本文件不主张什么

- 两个模块就是两个样本。上面写的都是**这两个重复过的形状**，不是一份契约。
- **只有 CRM 被端到端跑过**（`YD-2` 门禁）。`products` 只被生成和构建过，**没有**过同一道门。
- 范围机器的管理员判定是一个**占位**：「管理员」指用户 1，直到角色映射定下；而且**还没有**读宿主角色表这一步。

## 6. 真源在哪

| 问题 | 读 |
|---|---|
| 这个仓是什么、不做什么 | `KEELBASE-HOST.md` |
| 怎么弄出一个能跑的检出，以及路上的坑 | `KEELBASE-DEV.md` |
| 同一个问题在已冻结的 RuoYi 宿主上（写得更细） | `../../KeelBase-RuoYi/keelbase-docs/KEELBASE-AI-GUIDE.md` |
| 一个模块的门禁断言了什么 | `scripts/run-crm-customer-scenarios.mjs` |
| 哪些文件归 Spec、哪些是手写 | `bash scripts/gen-from-spec.sh --check-boundary` |
