// SPDX-License-Identifier: Apache-2.0

// 给运行时的调用方 id 配上名字，用的是**宿主自己的名录**。
//
// 运行时答一行审计时只给 `userId`、对这个人的其余一概不给——`AuditService.row()` 填八个字段、用户名不在
// 其中（参照实现是在服务端联了一个；本运行时没有这样的联结，故那一行带着一个 id 就到此为止）。但那个 id
// **就是**宿主自己的 `system_users.id`：身份缝造的是 `new Principal(String.valueOf(user.getId()), role)`。
// 所以控制台可以问**宿主自己的名录**来给调用方配名——这是**宿主的数据**在把一个名字补上，不是一个运行时
// 本该送、却没送的字段。
//
// **问的是 `/audit/caller-names`，不是 `/system/user/list`。** 后者要 `system:user:query`，而
// `system_users` 又受芋道按表名的部门数据权限收窄 ⇒ 一个只挂 `audit_reader` 的账号在那里只拿得到**它自己**，
// 「谁」那一格就只剩一个数字 id。宿主为此开的是一条窄读面：管理员或审计读者可读、只回 `id / username /
// nickname`。页面因此只需要一条请求，而且拿到的东西不比名字多。
//
// Puts a name on the runtime's caller id, using the host's own directory.
//
// The runtime answers an audit row with a `userId` and nothing else about the person — `AuditService.row()`
// fills eight fields and a username is not among them (the reference console joins one server-side; this
// runtime has no such join, so the row carries an id and stops there). But that id *is* this host's own
// `system_users.id`: the identity seam builds `new Principal(String.valueOf(user.getId()), role)`. So the
// console can name the caller by asking the host's own directory — this is the host's data filling a name
// in, not a field the runtime was supposed to send and did not.
//
// **It asks `/audit/caller-names`, not `/system/user/list`.** The latter needs `system:user:query`, and
// `system_users` is also narrowed by Yudao's table-keyed department data permission — so an account
// holding only `audit_reader` gets **itself alone** there and the "who" cell is left with a bare numeric
// id. The host opened a narrow face for this: readable by an administrator or an audit reader, answering
// `id / username / nickname` only. One request, and nothing more than a name comes back.

import { runtime } from '@/keelbase/runtime'

/** One row of the host's answer, only the parts a name needs. */
/** 宿主答复的一行，只取配名用得着的部分。 */
interface NamedUser {
  id: number | string
  nickname?: string
  username?: string
}

const cache = new Map<string, string>()

function nameOf(user: NamedUser): string {
  return user.nickname || user.username || String(user.id)
}

/**
 * 给这些 id 配名。名录不认识的 id、或失败的一次查询（一个不该读名录的调用方），**回落到 id 本身**——
 * 这里做的只是**补一个名字**，而名字取不到时页面**仍须**把它的行渲染出来。从不抛错。
 *
 * Names for the given ids. An id the directory does not know, or a lookup that fails (a caller who
 * may not read the directory), falls back to **the id itself** — this only ever adds a name, and a
 * page must still render its rows when the names cannot be had. Never throws.
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
    else out[id] = id // 不是数字 id：无从查，原样显示。 / not a numeric id: nothing to look up, show it as it is.
  }
  if (missing.length) {
    try {
      const answer = await runtime.get<NamedUser[]>('/audit/caller-names', {
        params: { ids: missing.join(',') },
      })
      const users = (answer.data || []) as NamedUser[]
      const byId = new Map(users.map((u) => [String(u.id), nameOf(u)]))
      for (const n of missing) {
        const name = byId.get(String(n)) ?? String(n)
        cache.set(String(n), name)
        out[String(n)] = name
      }
    } catch {
      // 名录拒绝了该调用方、或那次调用失败：用 id 顶替名字。
      // The directory refused this caller, or the call failed: ids stand in for names.
      for (const n of missing) out[String(n)] = String(n)
    }
  }
  return out
}
