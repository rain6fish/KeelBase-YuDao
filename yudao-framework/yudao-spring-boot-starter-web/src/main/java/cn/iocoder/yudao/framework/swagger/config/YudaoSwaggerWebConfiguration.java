package cn.iocoder.yudao.framework.swagger.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Swagger 的 Web MVC 配置。
 *
 * 解决 springdoc 3.0.3 + Spring Boot 4.x 的 ClassCastException：springdoc 的 /v3/api-docs endpoint
 * 声明返回 ResponseEntity&lt;byte[]&gt;，但实际返回 OpenAPI 对象。Spring 的 content negotiation 按声明类型
 * 选择 ByteArrayHttpMessageConverter，导致 LinkedHashMap 无法转换为 byte[]。
 *
 * 修复：用一个能处理 Object 的转换器替换 ByteArrayHttpMessageConverter，当 body 不是 byte[] 时回落为 JSON 序列化。
 *
 * @since 4.1.1
 */
@AutoConfiguration
public class YudaoSwaggerWebConfiguration implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 找到 ByteArrayHttpMessageConverter 并替换为智能版本
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof ByteArrayHttpMessageConverter) {
                converters.set(i, new SmartByteArrayHttpMessageConverter());
                break;
            }
        }
    }

    /**
     * 智能的 ByteArrayHttpMessageConverter：当 body 是 byte[] 时按原逻辑处理，否则回落为 JSON 序列化。
     */
    private static class SmartByteArrayHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
        
        private final MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        
        public SmartByteArrayHttpMessageConverter() {
            super(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return true;
        }

        @Override
        protected Object readInternal(Class<? extends Object> clazz, HttpInputMessage inputMessage)
                throws IOException, HttpMessageNotReadableException {
            return StreamUtils.copyToByteArray(inputMessage.getBody());
        }

        @Override
        protected void writeInternal(Object body, HttpOutputMessage outputMessage)
                throws IOException, HttpMessageNotWritableException {
            // 如果 body 是 byte[]，按原逻辑写
            if (body instanceof byte[]) {
                outputMessage.getBody().write((byte[]) body);
            } else {
                // 否则用 Jackson 序列化为 JSON
                jsonConverter.write(body, MediaType.APPLICATION_JSON, outputMessage);
            }
        }
    }

}
