package com.ai.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent Buyer 应用程序启动类。
 *
 * <p>负责初始化 Spring Boot 应用程序上下文，配置 MyBatis Mapper 扫描路径。
 *
 * @author AI Agent
 */
@MapperScan("com.ai.agent.persistence.mapper")
@SpringBootApplication
public class AgentBuyerApplication {

    /**
     * 应用程序入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentBuyerApplication.class, args);
    }
}
