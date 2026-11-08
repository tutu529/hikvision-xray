package com.kylin.hikvisionxray.sender;

import com.kylin.hikvisionxray.config.BridgeProperties;
import com.kylin.hikvisionxray.queue.SendUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 向业务接口 POST multipart(form-data)。
 *
 * 契约(对接已存在的接口):
 *   POST {target-url}
 *   image     = 图片二进制(字段名 image,文件名保留)
 *   lineId    = 设备配置的 line-id
 *   eventType = 预留,当前不传
 */
@Component
public class HttpSender {

    private static final Logger log = LoggerFactory.getLogger(HttpSender.class);

    private final RestTemplate restTemplate;
    private final BridgeProperties.Http httpProps;

    public HttpSender(BridgeProperties properties) {
        this.httpProps = properties.getHttp();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(httpProps.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(httpProps.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 发送一个单元。非 2xx 或网络异常抛出异常,由调用方决定重试。 */
    public void send(SendUnit unit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource image = new ByteArrayResource(unit.getImageData()) {
            @Override
            public String getFilename() {
                return unit.getImageFilename();
            }
        };
        form.add("image", image);
        form.add("lineId", unit.getLineId());
        // eventType 预留,当前不传

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(httpProps.getTargetUrl(), request, String.class);

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new IllegalStateException("业务接口返回异常状态码: " + response.getStatusCode());
        }
        log.debug("发送成功 unitId={} deviceId={} filename={}", unit.getUnitId(), unit.getDeviceId(), unit.getImageFilename());
    }
}
