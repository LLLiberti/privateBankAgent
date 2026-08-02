package com.privatebank;

import com.privatebank.config.JwtProperties;
import com.privatebank.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class})
public class PrivateBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivateBankApplication.class, args);
    }
}
