import com.example.outages.config.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties({
        SchedulerProperties.class,
        TargetProperties.class,
        SampleProperties.class,
        RetryProperties.class,
        OutputProperties.class,
        SendProperties.class
})
public class AppConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("campaign-");
        return scheduler;
    }

    @Bean
    public WebClient webClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        // ✅ lightweight JDK 11 HTTP client
        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder().build()))
                .exchangeStrategies(strategies)
                .build();
    }
}
