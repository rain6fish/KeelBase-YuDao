// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.server.keelbase;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers an OpenTelemetry SDK provider so the host's own trace utilities have a real trace id.
 *
 * <p>The monitor starter declares the OTel API and SDK as optional, and no provider is registered
 * today: `TracerUtils` therefore returns an empty string on every request. This configuration builds
 * a provider without an exporter — the id is what the audit link needs; exporting spans is a separate
 * concern — and publishes it as the global instance. A provider already registered (for example by an
 * agent) is kept rather than replaced.
 *
 * 注册一个 OpenTelemetry SDK provider，让宿主自己的 trace 工具有真实的 trace id。
 *
 * <p>monitor starter 把 OTel API 与 SDK 声明为 optional，当前也没有注册 provider：`TracerUtils`
 * 因此在每个请求上返回空串。本配置构建一个不带 exporter 的 provider —— 审计关联要的是那个 id，导出 span
 * 是另一件事 —— 并把它发布为全局实例。已经注册的 provider（例如 agent 设置的）保留、不替换。
 */
@Configuration
@ConditionalOnClass(OpenTelemetrySdk.class)
@Slf4j
public class HostTracingConfig {

    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider() {
        return SdkTracerProvider.builder().build();
    }

    @Bean
    public OpenTelemetry openTelemetry(@Qualifier("sdkTracerProvider") SdkTracerProvider provider) {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            GlobalOpenTelemetry.set(sdk);
        } catch (IllegalStateException alreadySet) {
            log.info("[HostTracingConfig][OpenTelemetry is already registered, keeping the existing one]");
        }
        return sdk;
    }
}
