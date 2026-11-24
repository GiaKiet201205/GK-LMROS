package vn.gk.logistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class LogisticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogisticsApplication.class, args);
    }

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder b) {
        return b.setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofMinutes(15)).build();
    }
}
