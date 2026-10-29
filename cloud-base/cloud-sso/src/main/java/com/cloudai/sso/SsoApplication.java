package com.cloudai.sso;

import com.cloudai.system.api.client.SystemUserClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * @author jy-root
 */
@SpringBootApplication
@EnableFeignClients(clients = {SystemUserClient.class})
public class SsoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsoApplication.class, args);
    }
}
