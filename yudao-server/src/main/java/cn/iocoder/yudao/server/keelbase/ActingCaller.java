// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import java.util.function.Supplier;

/**
 * The caller a tool's own data access runs as, for the window of that call.
 *
 * <p>Host-agnostic on purpose — it is a thread local and nothing else — which is why it moves to a
 * second host unchanged. A host whose row-scope aspects read a caller put one here; a host that
 * governs elsewhere can ignore it.
 *
 * 工具自身的数据访问在那个调用窗口里以谁的身份运行。
 *
 * <p>**刻意与宿主无关**——它就是一个 thread local、别无其他，所以换宿主原样搬。宿主若靠切面读调用方，
 * 就从这里取；在别处治理的宿主可以不理它。
 */
public final class ActingCaller {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActingCaller() {
    }

    public static <T> T as(String userId, Supplier<T> body) {
        String previous = CURRENT.get();
        CURRENT.set(userId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static String current() {
        return CURRENT.get();
    }
}
