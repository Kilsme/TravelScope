package com.travelscope.assessment;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
@Data @Component @ConfigurationProperties(prefix="travelscope.assessment")
public class AssessmentProperties {
    private boolean enabled = false;
    private boolean batchesEnabled = false;
    private boolean retentionEnabled = true;
    private int queueCapacity = 2048;
    private String priceVersion = "unconfigured";
    private Map<String,BigDecimal> toolPrices = new HashMap<>();
    private Map<String, Price> prices = new HashMap<>();
    @Data public static class Price { private BigDecimal inputPerMillion; private BigDecimal outputPerMillion; private BigDecimal cachedInputPerMillion; }
}
