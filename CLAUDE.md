# KeelBase-YuDao · 本仓约定 / Conventions for this repository

> 本文件只记**本仓特有**的约定。用户级 `CLAUDE.md`（对所有项目生效的那份）里**未**被本文件覆盖的条款，**照旧生效**。

## 1. 双语块的顺序：**中文在前、英文在后**（本仓特有 · 2026-10-14 用户定）

**本仓内，凡把中英两种语言放在同一份产出里 —— 提交信息、文档、新写的代码注释 —— 一律：**

- **中文块在前，英文块在后**；中英**各自成完整块**、**块内不掺杂**（专有名词 / 代码标识符 / 命令除外）；
- **标题行例外** —— 允许中英混排；
- 只写一种语言**不算违规**（这一条管的是**两种都写时的顺序**，不是"必须双语"）；
- **不带 `Co-Authored-By`**；
- **存量不回溯改写** —— 只对**本次新写 / 本次改到**的内容生效；
- **其它一切不变** —— 本条只改**顺序**，不改别的任何一条。

> **与用户级 `CLAUDE.md` 的关系**：用户级写的是「**英文在前、中文在后**」；**在本仓内，这一条的顺序相反**。其余条款（整块、不掺杂、标题可混排、不带 `Co-Authored-By`、不回溯存量）**逐条照旧**。

**提交信息的完整版式（本仓）**：

```
<type>(<scope>): <English summary> — <中文摘要>      ← 标题：可中英混排，也可只写一种

<中文正文：改了什么 / 为什么>                          ← 一整段中文（在前）

<English body: what changed / why>                   ← 一整段英文（在后）
```

## 2. 文件对（`X.md` + `X.zh-CN.md`）：**不变**

`keelbase-docs/` 用的是**一份英文 + 一份中文**的**文件对**写法（如 `KEELBASE-DEV.md` 与 `KEELBASE-DEV.zh-CN.md`），**不是**"两种语言混在同一份里"。**这种写法保持原样** —— 上面第 1 条管的是**同一份产出里两种语言并存**的情形。

---

## English

This repository keeps one convention of its own, and it only reorders bilingual blocks.

**Where both languages appear in a single artifact -- a commit message, a document, a newly written code comment -- the Chinese block comes first and the English block second.** Each language stays a whole block of its own, undiluted except for proper nouns, identifiers and commands. A title line may still mix the two. Writing only one language is not a violation: the rule governs the order when both are written, not whether they must be. No `Co-Authored-By`. Existing text is not rewritten to match -- the rule applies to what is newly written or newly touched. Nothing else changes: this clause changes the order alone.

The user-level `CLAUDE.md` says English first; **inside this repository that one clause is reversed**, and every other clause of it stands exactly as written.

```
<type>(<scope>): <English summary> — <中文摘要>      <- title: may mix, or be one language

<中文正文>                                            <- the Chinese body, first

<English body>                                       <- the English body, second
```

The paired files under `keelbase-docs/` (an English `X.md` beside a Chinese `X.zh-CN.md`) are two separate files rather than one mixed one, and they are left unchanged: clause 1 is about both languages sharing one artifact.
