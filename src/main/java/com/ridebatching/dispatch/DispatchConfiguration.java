package com.ridebatching.dispatch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DispatchConfiguration {
    @Bean
    public Clock dispatchClock() { return Clock.systemUTC(); }
}
