// SPDX-License-Identifier: Apache-2.0

// 一个时刻，按它会来的**两种**形状显示 —— 因为控制台读的两条链，各用一种。
//
// 运行时答的是 ISO 串（`2026-10-15T13:31:22Z`）；**宿主自己那条面答的是毫秒数**（yudao 的 Jackson 把日期写成
// 时间戳，它自己的界面也就按数字格式化）。同名的那一列因此可能拿到两种东西，而控制台原先那份只处理了串：
// `v.replace('T',' ')` 对**数字**没有这个方法，于是那一格**在渲染时抛错、空着** —— 读表的人看到的是「这一行的
// 值整体左移了一格」，也就是一张表头和内容错位的表。
//
// 所以这里按**形状**分流：数字走 dayjs（本地时区，`YYYY-MM-DD HH:mm:ss`），串仍按原来那样把 `T` 换成空格、
// 截到秒（**一字不改**，否则运行时的那些列会集体换一种写法）。认不出来的一律给破折号：一格空的比一个编出来的
// 时间好。
//
// One moment, in the two shapes it actually arrives in — because the console reads two trails and each
// answers its own way. The runtime writes ISO strings; the host's own API writes epoch milliseconds
// (yudao's Jackson, which its own UI formats as a number too). A copy of this helper that only handled
// strings crashed on the number and left the cell blank, which reads as a row whose values have shifted
// one column to the left. Numbers go through dayjs; strings keep the old treatment exactly; anything
// else is a dash rather than an invented time.
import dayjs from 'dayjs'

export function timeText(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'number') {
    const at = dayjs(v)
    return at.isValid() ? at.format('YYYY-MM-DD HH:mm:ss') : '—'
  }
  return String(v).replace('T', ' ').slice(0, 19)
}
