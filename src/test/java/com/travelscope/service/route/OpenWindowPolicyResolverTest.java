package com.travelscope.service.route;

import com.travelscope.service.route.dto.OpenWindowPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenWindowPolicyResolverTest {

    private final OpenWindowPolicyResolver resolver = new OpenWindowPolicyResolver();

    static Stream<Case> policies() {
        return Stream.of(
                new Case("便利店", "24小时", OpenWindowPolicy.OpenStatus.KNOWN_24H, "00:00", "23:59"),
                new Case("苏州博物馆", "09:00-17:00", OpenWindowPolicy.OpenStatus.KNOWN_OPEN, "09:00", "17:00"),
                new Case("室内展馆", null, OpenWindowPolicy.OpenStatus.UNKNOWN, null, null),
                new Case("九溪烟树", null, OpenWindowPolicy.OpenStatus.DAYLIGHT_ONLY, "08:00", "18:00"),
                new Case("西湖夜景", null, OpenWindowPolicy.OpenStatus.UNKNOWN, null, null),
                new Case("钱江新城灯光秀", null, OpenWindowPolicy.OpenStatus.UNKNOWN, null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("policies")
    void resolvesExplicitAndDerivedOpenPolicies(Case value) {
        OpenWindowPolicy policy = resolver.resolve(value.name, value.openHours, "fixture");

        assertThat(policy.status()).isEqualTo(value.status);
        if (value.start == null) {
            assertThat(policy.windows()).isEmpty();
        } else {
            assertThat(policy.windows()).singleElement().satisfies(window -> {
                assertThat(window.start()).isEqualTo(value.start);
                assertThat(window.end()).isEqualTo(value.end);
            });
        }
        assertThat(policy.source()).isEqualTo("fixture");
        assertThat(policy.fetchedAt()).isNotNull();
    }

    private record Case(String name, String openHours, OpenWindowPolicy.OpenStatus status,
                        String start, String end) { }
}
