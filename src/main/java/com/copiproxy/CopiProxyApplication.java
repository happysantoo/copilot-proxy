package com.copiproxy;

import com.copiproxy.config.CopiProxyProperties;
import com.copiproxy.config.ProxyEnvironmentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CopiProxyProperties.class)
public class CopiProxyApplication {
    public static void main(String[] args) {
        ProxyEnvironmentConfig.apply();
        SpringApplication.run(CopiProxyApplication.class, args);
    }
}
