package com.example.retention.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI retentionOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("通话保留与调阅数据服务")
                .version("1.0.0")
                .description("合规证据的接入账、证据关系图、规则目录、义务解释（双路线）、"
                        + "调阅快照与复演、处置编排、模拟执行与执行对账。"));
    }
}
