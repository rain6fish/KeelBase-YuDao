// SPDX-License-Identifier: Apache-2.0

// Masks a record's own text before this console prints it.
//
// The console is the administrative surface, and the project's own red line puts one thing out of
// bounds on it: what a record is *about* — the text a person entered — must not be printed there
// (CLAUDE.md §5.5 ②; the reference's admin API masks the same way server-side). A reader still needs to
// tell one record from another, so the first character stays: enough to recognise a row, not enough to
// read the record. Nothing here is reversible, and nothing here looks the text up again.
//
// 在控制台打印之前，把**记录自己的文字**掩掉。
//
// 控制台是管理面，而项目自己的红线在它上面划出一件事：一条记录**讲的是什么** —— 人填进去的那段文字 ——
// **不得**在这里打印（`CLAUDE.md` §5.5 ②；参照实现的管理端 API 也是这样在服务端掩的）。读者仍要能分辨
// 两条记录，故**首字符留着**：够认出一行，不够读出那条记录。这里没有任何一处可逆，也没有任何一处再去
// 把那段文字查回来。

/**
 * A title with its content masked: the first character kept, the rest replaced. A blank title masks to
 * a dash rather than to a lone asterisk, so "there was nothing" and "there was something" stay apart.
 *
 * 标题，内容掩码：**留首字符、其余替换**。空标题掩成一个破折号、而不是一个孤零零的星号 —— 「本来就没有」
 * 与「有东西」因此分得开。
 */
export function maskTitle(text: string | null | undefined): string {
  if (!text) return '—'
  const head = [...text].slice(0, 1).join('')
  return `${head}****`
}
