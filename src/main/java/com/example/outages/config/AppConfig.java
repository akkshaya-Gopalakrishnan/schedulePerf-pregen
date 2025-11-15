package com.example.outages.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({SchedulerProperties.class, TargetProperties.class, SampleProperties.class, RetryProperties.class, OutputProperties.class, SendProperties.class})
public class AppConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("campaign-");
        return scheduler;
    }

    @Bean
    public ClientHttpConnector httpConnector() {
        ClientHttpConnector connector = tryBuildJdkConnector();
        return connector != null ? connector : new HttpComponentsClientHttpConnector();
    }

    private ClientHttpConnector tryBuildJdkConnector() {
        try {
            Class<?> type = Class.forName("org.springframework.http.client.reactive.JdkClientHttpConnector");
            return (ClientHttpConnector) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException ex) {
            return null;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to instantiate JDK ClientHttpConnector", ex);
        }
    }

    @Bean
    public WebClient webClient(ClientHttpConnector connector) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return WebClient.builder()
                .clientConnector(connector)
                .exchangeStrategies(strategies)
                .build();
    }
}
