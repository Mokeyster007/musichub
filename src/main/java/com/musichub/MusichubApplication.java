package com.musichub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.musichub.mapper")
@EnableScheduling  // <--- 开启定时任务
public class MusichubApplication {

	public static void main(String[] args) {
		SpringApplication.run(MusichubApplication.class, args);
	}

}
