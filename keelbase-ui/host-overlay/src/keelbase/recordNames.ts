// SPDX-License-Identifier: Apache-2.0

// Asks this host what a record is called, so the console can show it MASKED.
//
// The runtime's ledger says which record a write landed on — a type and an id — but it names the
// record only for its own sample module and answers nothing for the rest. The host knows its own
// tables, though, and this is the same move the console already makes for a caller's name: ask the
// host's own directory rather than wait for a field the runtime does not send. What comes back is
// **masked before it is drawn** (`mask.ts`); nothing here is ever shown as it arrived.
//
// 问宿主「这条记录叫什么」，好让控制台**掩码之后**显示它。
//
// 运行时的台账说得出一次写落在**哪条**记录上 —— 一个类型加一个 id —— 但它只给**自己样例模块**的记录配
// 名字、对其余的一律不答。而宿主认得自己的表；这与控制台为**调用方姓名**做的同一手：问宿主自己的名录，
// 而不是等一个运行时并不送的字段。回来的东西**画出来之前先掩码**（`mask.ts`）；这里没有任何一处按原样显示。

import request from '@/config/axios'

/** The record types this console can name, and where the host keeps each. */
/** 本控制台叫得出名字的记录类型，以及宿主把每一个放在哪。 */
const ENDPOINTS: Record<string, string> = {
  crm_customer: '/crm/customer/get',
}

const cache = new Map<string, string>()

function keyOf(resultType: string | null | undefined, resultId: string | number | null | undefined): string {
  return `${resultType ?? ''}#${resultId ?? ''}`
}

/**
 * The record's own name, or an empty string when this host cannot answer — a type it does not know, a
 * row it does not hold, a caller it refuses. Never throws: an empty answer means the page draws the
 * type and the id and no name, which is what it would have drawn anyway.
 *
 * 记录自己的名字；宿主答不上来时回空串 —— 不认识的类型、手里没有的那一行、被拒绝的调用方。从不抛错：
 * 空答案意味着页面画类型与 id、不画名字，而那本来就是它会画的东西。
 */
export async function resolveRecordName(
  resultType: string | null | undefined,
  resultId: string | number | null | undefined,
): Promise<string> {
  const url = resultType ? ENDPOINTS[resultType] : undefined
  if (!url || resultId === null || resultId === undefined) return ''
  const key = keyOf(resultType, resultId)
  const hit = cache.get(key)
  if (hit !== undefined) return hit
  let name = ''
  try {
    const data = (await request.get({ url, params: { id: resultId } })) as { name?: string } | null
    name = data?.name ?? ''
  } catch {
    name = ''
  }
  cache.set(key, name)
  return name
}
