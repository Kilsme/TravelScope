package com.travelscope.chat;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "travelscope.reconnect")
public class ReconnectProperties {
    private boolean enabled = true;
    private int preparationSeconds = 60;
    private int queueSeconds = 30;
    private int executionSeconds = 900;
    private int retentionHours = 24;
    private int sseTimeoutSeconds = 120;
}
