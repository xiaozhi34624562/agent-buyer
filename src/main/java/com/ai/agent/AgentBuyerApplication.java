package com.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.ai.agent.persistence.mapper")
@SpringBootApplication
public class AgentBuyerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentBuyerApplication.class, args);
    }
}
