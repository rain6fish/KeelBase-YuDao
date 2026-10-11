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
import java.util.List;

/**
 * Swagger 的 Web MVC 配置。
 *
 * 解决 springdoc 3.0.3 + Spring Boot 4.x 的 ClassCastException：springdoc 的 /v3/api-docs endpoint
 * 声明返回 ResponseEntity&lt;byte[]&gt;，但实际返回 OpenAPI 对象。Spring 的 content negotiation 按声明类型
 * 选择 ByteArrayHttpMessageConverter，导致 LinkedHashMap 无法转换为 byte[]。
 *
 * 修复：用一个智能转换器替换 ByteArrayHttpMessageConverter，覆盖所有可能抛出 ClassCastException 的方法，
 * 当 body 不是 byte[] 时回落为 JSON 序列化。
 *
 * @since 4.1.1
 */
@AutoConfiguration
public class YudaoSwaggerWebConfiguration implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof ByteArrayHttpMessageConverter) {
                converters.set(i, new SmartByteArrayHttpMessageConverter());
                break;
            }
        }
    }

    /**
     * 智能的 ByteArrayHttpMessageConverter：当 body 是 byte[] 时按原逻辑处理，否则回落为 JSON 序列化。
     * 覆盖 getContentLength() 等方法，避免在 body 不是 byte[] 时抛出 ClassCastException。
     */
    private static class SmartByteArrayHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
        
        private final MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        
        public SmartByteArrayHttpMessageConverter() {
            super(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);
        }

        @Override
        public boolean supports(Class<?> clazz) {
            // 只处理 byte[]，让其他类型走 Jackson
            return byte[].class.equals(clazz);
        }

        @Override
        protected Object readInternal(Class<? extends Object> clazz, HttpInputMessage inputMessage)
                throws IOException, HttpMessageNotReadableException {
            return StreamUtils.copyToByteArray(inputMessage.getBody());
        }

        @Override
        protected Long getContentLength(Object body, MediaType contentType) {
            // 如果 body 是 byte[]，返回长度；否则返回 null 让 Spring 使用 chunked transfer
            return (body instanceof byte[]) ? (long) ((byte[]) body).length : null;
        }

        @Override
        protected void writeInternal(Object body, HttpOutputMessage outputMessage)
                throws IOException, HttpMessageNotWritableException {
            if (body instanceof byte[]) {
                outputMessage.getBody().write((byte[]) body);
            } else {
                // 不是 byte[]，用 Jackson 序列化为 JSON
                jsonConverter.write(body, MediaType.APPLICATION_JSON, outputMessage);
            }
        }
    }

}
