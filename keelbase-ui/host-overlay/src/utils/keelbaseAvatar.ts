// SPDX-License-Identifier: Apache-2.0
//
// What the change-avatar dialog offers, and the one place that remembers the last image a person
// uploaded. Both live here rather than inside a component because the dialog and the profile page
// both need them, and they have to agree on how a preset is recognised and where an upload is kept.
//
// 换头像弹窗提供哪些头像，以及**唯一**记住「这个人上次上传的那张图」的地方。两样都放在这里而不放进
// 组件，是因为弹窗与个人页都要用，且两者必须对「怎么认出一个预置」与「上传记在哪」保持一致。

// Site-relative on purpose: these ship with the console, so the same choice has to read as "current"
// on whatever origin serves it. They are DiceBear's, across five of its styles — each file carries
// its own style, author and licence in the metadata an image editor writes, and `NOTICE` names them.
// 刻意写成站点相对：它们随控制台一起发出，故同一个选择在任何来源下都得读作「当前用的」。它们是
// DiceBear 的，跨它的五个风格——每个文件的作者与许可写在图像元数据里，`NOTICE` 里也点名列了。
export const AVATAR_PRESETS = [
  '/keelbase-avatar-01.png',
  '/keelbase-avatar-02.png',
  '/keelbase-avatar-03.png',
  '/keelbase-avatar-04.png',
  '/keelbase-avatar-05.png',
  '/keelbase-avatar-06.png',
  '/keelbase-avatar-07.png',
  '/keelbase-avatar-08.png',
  '/keelbase-avatar-09.png',
  '/keelbase-avatar-10.png',
  '/keelbase-avatar-11.png',
  '/keelbase-avatar-12.png',
  '/keelbase-avatar-13.png',
  '/keelbase-avatar-14.png',
  '/keelbase-avatar-15.png',
  '/keelbase-avatar-16.png',
  '/keelbase-avatar-17.png',
  '/keelbase-avatar-18.png',
  '/keelbase-avatar-19.png',
  '/keelbase-avatar-20.png'
]

// The mark an avatar with no image of its own falls back to — the stylesheet points at this file,
// and the seed gives the administrator it. It is nobody's upload.
// **自己没有图**的头像所兜底的那个标记——样式表指向这个文件，种子也把它给了管理员。它不是谁的上传。
export const DEFAULT_AVATAR = '/keelbase-avatar.svg'

// Offered before this dialog existed, and no longer offered. A stored avatar may still name one, so
// the files stay shipped and the names stay recognised — otherwise that person's avatar stops
// resolving and the dialog starts calling it their own upload. Retiring a choice is not the same as
// deleting it.
// 本弹窗存在之前提供过、现在不再提供的那些。已存的头像仍可能指着一个，故文件继续发、名字继续被认出——
// 否则那个人的头像就不再解析得出来，弹窗还会把它说成是对方自己上传的。**撤下一个选择，不等于删掉它。**
//
// The ten `.svg` shapes are retired rather than deleted because of exactly that: the administrator of
// this deployment had one stored before the set was replaced.
// 那十个 `.svg` 的 shapes 是**撤下而非删掉**，正是因为这个：本部署的管理员在这套被换掉之前就存着一个。
export const RETIRED_PRESETS = [
  '/keelbase-avatar-blue.svg',
  '/keelbase-avatar-teal.svg',
  '/keelbase-avatar-graphite.svg',
  '/keelbase-avatar-warm.svg',
  '/keelbase-avatar-01.svg',
  '/keelbase-avatar-02.svg',
  '/keelbase-avatar-03.svg',
  '/keelbase-avatar-04.svg',
  '/keelbase-avatar-05.svg',
  '/keelbase-avatar-06.svg',
  '/keelbase-avatar-07.svg',
  '/keelbase-avatar-08.svg',
  '/keelbase-avatar-09.svg',
  '/keelbase-avatar-10.svg'
]

const STORAGE_PREFIX = 'keelbase.avatar.lastUpload'

const storageKey = (userId?: number | string) => `${STORAGE_PREFIX}.${userId ?? ''}`

// Matched on the site-relative tail: the profile API validates this field as a URL, so what is
// stored is absolute, and the answer must not depend on which origin served the console.
// 按站点相对的尾部匹配：资料接口把该字段按 URL 校验，故**存下来的是绝对地址**，而这个判断不能取决于
// 控制台是哪个源服务出来的。
export const isPreset = (url: string) =>
  [...AVATAR_PRESETS, ...RETIRED_PRESETS].some((preset) => url.endsWith(preset))

// An avatar this person put there themselves, as opposed to one the console supplies. The default
// mark is excluded: a fresh account carries it, and offering it back under "last uploaded" would be
// a lie about where it came from.
// 这个人**自己**放上去的头像，与「控制台自带的那些」相对。默认标记不算：新账号就带着它，把它当作
// 「上次上传」提供回来，是在它的来处上说了假话。
export const isOwnImage = (url: string) =>
  !!url && !isPreset(url) && !url.endsWith(DEFAULT_AVATAR)

// Empty when this browser knows of no upload, or when storage cannot be read at all.
// 这个浏览器不知道有上传时为**空**；存储读不到时同样为空。
export const readLastUpload = (userId?: number | string): string => {
  try {
    return window.localStorage.getItem(storageKey(userId)) || ''
  } catch {
    return ''
  }
}

export const rememberUpload = (url: string, userId?: number | string) => {
  try {
    window.localStorage.setItem(storageKey(userId), url)
  } catch {
    // Private mode, a full quota, a blocked origin — none of them is worth failing a change over.
    // 无痕模式、配额用尽、来源被拦——都不该成为一次换头像失败的理由。
  }
}
