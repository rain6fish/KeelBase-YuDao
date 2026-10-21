// SPDX-License-Identifier: Apache-2.0

// The role rule, with no Vue and no host imports, so it can be run as a test.
//
// `audit_reader` is the one identity this deployment gives read grants only, so it is the one role
// that turns write controls off. Everybody else — including the administrator and an account with no
// roles at all — keeps the page as it was; this rule does not invent a second, narrower console.
//
// 角色规则，不依赖 Vue、也不依赖宿主 import，故可以被当成测试直接跑。
//
// `audit_reader` 是本部署唯一只给读授权的身份，故它也是唯一关掉写控件的角色。其余（含管理员与
// 完全没有角色的账号）看到的页面与此前一样；这条规则不会发明第二个更窄的控制台。

export const AUDIT_READER_ROLE = 'audit_reader'

/**
 * True unless the caller carries `audit_reader`.
 *
 * 除非调用方带 `audit_reader`，否则为 true。
 */
export function canWriteWithRoles(roles) {
  return !(roles ?? []).includes(AUDIT_READER_ROLE)
}
