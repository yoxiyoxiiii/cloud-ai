package com.cloudai.bpmn;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@MapperScan("com.cloudai.bpmn.mapper")
@EnableFeignClients
public class BpmnApplication {

    public static void main(String[] args) {
        SpringApplication.run(BpmnApplication.class, args);
    }
}
