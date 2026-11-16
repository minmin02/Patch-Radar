package io.patchradar.collector.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "patchradar.poll.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
