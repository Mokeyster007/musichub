package com.musichub.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint("http://localhost:9000") // 注意：API端口是9000，控制台端口是9001
                .credentials("minioadmin", "minioadmin")
                .build();
    }
}
