package dev.gamersden;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.common.config.GamersDenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GamersDenProperties.class, AuthProperties.class})
public class GamersDenApplication {

    public static void main(String[] args) {
        SpringApplication.run(GamersDenApplication.class, args);
    }
}
