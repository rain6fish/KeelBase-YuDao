// SPDX-License-Identifier: Apache-2.0

// Who may see this console's write controls.
//
// The host's role list is the source of truth, and the one role that matters is `audit_reader`: the
// identity this deployment gives read grants only. Every write control is absent for it rather than
// merely failing on the server — a button that answers 403 is a control the reader was not supposed
// to be offered. Every other role keeps the page exactly as it was, including the administrator.
//
// The predicate itself lives in `consoleRoleCore.mjs`, which has no Vue import, so `node --test` can
// run it without a build. This file only binds it to the host's store.
//
// The server's own refusals are not changed by this: they remain the authority, and a direct call to
// a write endpoint still answers 403 for the reader.
//
// 谁看得见本控制台的写控件。
//
// 宿主的角色清单是事实来源，唯一要紧的角色是 `audit_reader`：本部署只给读授权的那个身份。对它而言
// 每个写控件都**不出现**，而不是点了才被服务端拒 —— 一个答 403 的按钮，本来就不该递到这位读者面前。
// 其余角色（含管理员）看到的页面与此前完全一样。
//
// 谓词本身住在 `consoleRoleCore.mjs`，它不 import Vue，故 `node --test` 不用构建就能跑；本文件只把它
// 接到宿主的 store 上。
//
// 这不改服务端自己的拒绝：它仍是权威，直接打写端点对这位读者仍旧答 403。
import { computed } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { AUDIT_READER_ROLE, canWriteWithRoles } from './consoleRoleCore.mjs'

export { AUDIT_READER_ROLE, canWriteWithRoles }

/**
 * `canWrite` is false for the audit reader and true for everybody else. It reads the role list on
 * every access, so a role change lands on the next render without a page-specific copy of the rule.
 *
 * `canWrite` 对审计读者为 false、其余为 true。它每次访问都读角色清单，故换角色在下一次渲染即生效，
 * 不需要每个页面各存一份规则。
 */
export function useConsoleWriteAllowed() {
  const userStore = useUserStore()
  const readOnly = computed(() => !canWriteWithRoles(userStore.getRoles))
  return { readOnly, canWrite: computed(() => !readOnly.value) }
}
