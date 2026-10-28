package com.travelscope.service.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewActionRouterTest {
    private final ReviewActionRouter router = new ReviewActionRouter();

    @Test void mapsPoiReasons() {
        assertThat(router.route("DUPLICATE_POI").allowedActions()).containsExactly("revise_poi_selection");
        assertThat(router.route("PREFERENCE_MISSING").allowedActions()).containsExactly("revise_poi_selection");
    }
    @Test void mapsRouteReasons() {
        assertThat(router.route("OPENING_HOUR_CONFLICT").allowedActions()).containsExactly("plan_route");
        assertThat(router.route("LATE_OUTDOOR_ACTIVITY").allowedActions()).containsExactly("plan_route");
        assertThat(router.route("DAILY_BALANCE").allowedActions()).containsExactly("plan_route");
    }
    @Test void mapsHotelReasonToOrderedActions() {
        assertThat(router.route("HOTEL_BUDGET_MISMATCH").allowedActions())
                .containsExactly("searchHotels", "assemble_itinerary");
    }
    @Test void budgetArithmeticIsCodeOnly() {
        var route = router.route("BUDGET_ARITHMETIC_MISMATCH");
        assertThat(route.status()).isEqualTo(ReviewActionRouter.Status.CODE_RECALCULATE);
        assertThat(route.llmAllowed()).isFalse();
        assertThat(route.mustRecheck()).isTrue();
    }
    @Test void draftMissingIsNotReadyAndNeverRevised() {
        var route = router.route("DRAFT_MISSING");
        assertThat(route.status()).isEqualTo(ReviewActionRouter.Status.NOT_READY);
        assertThat(route.allowedActions()).isEmpty();
        assertThat(route.mustRecheck()).isFalse();
    }
}
