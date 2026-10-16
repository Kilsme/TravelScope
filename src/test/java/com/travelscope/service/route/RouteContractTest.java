package com.travelscope.service.route;

import com.travelscope.service.route.dto.PoiCandidate;
import com.travelscope.service.route.dto.RouteConstraints;
import com.travelscope.service.route.dto.TimeMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1.1 验证：路线求解契约 DTO 的构造不变式。
 */
class RouteContractTest {

    @Test
    void daysMustBePositive() {
        assertThatThrownBy(() -> RouteConstraints.ofDays(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days");
    }

    @Test
    void maxDailyCommuteMustBePositive() {
        assertThatThrownBy(() -> new RouteConstraints(2, "09:00", "21:00", 0, 10,
                null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDailyCommuteMin");
    }

    @Test
    void openWindowMustBePaired() {
        // 有 openStart 无 openClose → 拒
        assertThatThrownBy(() -> new PoiCandidate("灵隐寺", 120.09, 30.24,
                "07:00", null, 150, 4.7, "RAG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成对");
        // 皆空 → 全天开放，合法
        new PoiCandidate("西湖", 120.14, 30.24, null, null, 240, 4.8, "RAG");
    }

    @Test
    void dailyWindowMustBeOrdered() {
        assertThatThrownBy(() -> new RouteConstraints(2, "21:00", "09:00", 90, 10,
                null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyStart");
    }

    @Test
    void hotelTripleMustBePaired() {
        assertThatThrownBy(() -> new RouteConstraints(2, "09:00", "21:00", 90, 10,
                "西湖某酒店", 120.14, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("酒店三件套");
    }

    @Test
    void ofDaysCarriesDefaults() {
        RouteConstraints c = RouteConstraints.ofDays(3);
        assertThat(c.days()).isEqualTo(3);
        assertThat(c.dailyStart()).isEqualTo("09:00");
        assertThat(c.dailyEnd()).isEqualTo("21:00");
        assertThat(c.maxDailyCommuteMin()).isEqualTo(90);
        assertThat(c.transferBufferMin()).isEqualTo(10);
        assertThat(c.preferTransit()).isTrue();
        assertThat(c.hotelName()).isNull();

        RouteConstraints withHotel = c.withHotel("西湖某酒店", 120.14, 30.24);
        assertThat(withHotel.hotelName()).isEqualTo("西湖某酒店");
        assertThat(withHotel.days()).isEqualTo(3);
    }

    @Test
    void timeMatrixRejectsRaggedShape() {
        assertThatThrownBy(() -> new TimeMatrix(List.of("A", "B", "C"),
                new long[][]{{0, 5, 8}, {5, 0, 3}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void timeMatrixHasContentEquality() {
        TimeMatrix a = new TimeMatrix(List.of("A", "B"), new long[][]{{0, 300}, {300, 0}});
        TimeMatrix b = new TimeMatrix(List.of("A", "B"), new long[][]{{0, 300}, {300, 0}});
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.get(0, 1)).isEqualTo(300);
        assertThat(a.size()).isEqualTo(2);
        assertThat(a.indexOf("B")).isEqualTo(1);
    }
}
