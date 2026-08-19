package com.musichub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * 配置带超时控制的 RestTemplate Bean
     * - 连接超时：3秒（建立 TCP 连接的最大等待时间）
     * - 读取超时：5秒（从服务器读取数据的最大等待时间）
     */
    @Bean("timeoutRestTemplate")
    public RestTemplate timeoutRestTemplate() {
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // 连接超时：3秒
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout(3000);

        // 读取超时：5秒
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout(5000);

        return new RestTemplate(factory);
    }
}
