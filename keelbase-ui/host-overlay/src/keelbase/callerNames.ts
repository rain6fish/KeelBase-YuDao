// SPDX-License-Identifier: Apache-2.0

// Puts a name on the runtime's caller id — **the host does the joining**, the console only asks.
//
// The runtime answers an audit row with a `userId` and nothing else about the person — `AuditService.row()`
// fills eight fields and a username is not among them. But that id *is* this host's own `system_users.id`,
// so the host can name it. This module used to ask the host's user directory directly
// (`GET /system/user/list`), which needs `system:user:query`; the reader roles do not hold it, so the
// pages fell back to bare numbers — finding F-14 from M7's third reader session. On 2026-10-27 the user
// chose the server-side join: `GET /admin-api/keelbase/caller-names` answers names **without granting
// anything new**, and `YudaoCallerDirectory` bounds what it will name to people the caller could already
// meet in a ledger.
//
// 给运行时的调用方 id 配上名字——**联结由宿主做**，控制台只是问一句。
//
// 运行时答一行审计时只给 `userId`、对这个人的其余一概不给（`AuditService.row()` 填八个字段、用户名不在
// 其中）。但那个 id **就是**宿主自己的 `system_users.id`，故宿主能给它配名。本模块原先直接问宿主的用户
// 名录（`GET /system/user/list`），那个端点要 `system:user:query`；读者角色没有它，页面于是只剩一串数字
// ——M7 第三位读者场 F-14。2026-10-27 用户裁定走**服务端联名**：`GET /admin-api/keelbase/caller-names`
// 在**不新增任何授予**的前提下答名字，而答到什么范围由 `YudaoCallerDirectory` 裁到「这个调用方本来就能在
// 台账里遇到的人」。

import request from '@/config/axios'

/** 宿主答复的一行：一个 id 与它的名字。 / One row of the host's answer: an id and its name. */
interface NamedCaller {
  id: string | number
  name: string
}

const cache = new Map<string, string>()

/**
 * Names for the given ids. An id the host does not name — not in a ledger, another tenant, or a lookup
 * that fails — falls back to **the id itself**. This only ever adds a name, and a page must still render
 * its rows when the names cannot be had. Never throws.
 *
 * 给这些 id 配名。宿主不给名字的 id——不在账本里、别的租户、或那次查询失败——**回落到 id 本身**。这里做
 * 的只是**补一个名字**，而名字取不到时页面**仍须**把它的行渲染出来。从不抛错。
 */
export async function resolveCallerNames(
  ids: (string | number | null | undefined)[],
): Promise<Record<string, string>> {
  const wanted = [
    ...new Set(ids.filter((v) => v !== null && v !== undefined && v !== '').map((v) => String(v))),
  ]
  const out: Record<string, string> = {}
  const missing: number[] = []
  for (const id of wanted) {
    const hit = cache.get(id)
    if (hit !== undefined) out[id] = hit
    else if (/^\d+$/.test(id)) missing.push(Number(id))
    else out[id] = id // not a numeric id: nothing to look up, show it as it is. / 不是数字 id：无从查，原样显示。
  }
  if (missing.length) {
    try {
      const rows = (await request.get({
        url: '/keelbase/caller-names',
        params: { ids: missing.join(',') },
      })) as NamedCaller[]
      const byId = new Map((rows || []).map((row) => [String(row.id), row.name]))
      for (const n of missing) {
        const name = byId.get(String(n)) ?? String(n)
        cache.set(String(n), name)
        out[String(n)] = name
      }
    } catch {
      // The call failed: ids stand in for names. / 调用失败：用 id 顶替名字。
      for (const n of missing) out[String(n)] = String(n)
    }
  }
  return out
}
