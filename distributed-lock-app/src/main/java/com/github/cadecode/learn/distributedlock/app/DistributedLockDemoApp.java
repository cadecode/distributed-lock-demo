package com.github.cadecode.learn.distributedlock.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;

/**
 * @author Cade Li
 * @date 2022/2/13
 * @description 启动类
 */
@SpringBootApplication
@ComponentScan("com.github.cadecode")
@MapperScan("com.github.cadecode.mysql")
public class DistributedLockDemoApp extends SpringBootServletInitializer {
    public static void main(String[] args) {
        SpringApplication.run(DistributedLockDemoApp.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(DistributedLockDemoApp.class);
    }
}
