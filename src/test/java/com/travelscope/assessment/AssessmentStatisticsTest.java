package com.travelscope.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssessmentStatisticsTest {
    @Test void invalidComponentScopesNeverQueryTheDatabase(){
        var jdbc=mock(JdbcTemplate.class);var queries=new AssessmentQueries(jdbc);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,()->queries.components("ASSESSMENT",null,null,"MODEL",null)).getStatusCode());
        assertThrows(ResponseStatusException.class,()->queries.components("ONLINE","LIVE",null,"MODEL",null));
        assertThrows(ResponseStatusException.class,()->queries.components("ONLINE",null,"UNKNOWN","MODEL",null));
        assertThrows(ResponseStatusException.class,()->queries.components("ONLINE",null,null,"MODEL_CALL",null));
        verifyNoInteractions(jdbc);
    }
    @Test void componentEndpointRequiresAdministratorAndRejectsMalformedRunFilters() throws Exception {
        var queries=mock(AssessmentQueries.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AssessmentController(queries,mock(AssessmentBatches.class),mock(AssessmentActions.class))).build();
        mvc.perform(get("/admin/assessment/components").requestAttr("userRole","user")).andExpect(status().isForbidden());
        verifyNoInteractions(queries);
        mvc.perform(get("/admin/assessment/components").requestAttr("userRole","admin").param("kind","MODEL").param("phase","PLANNING")).andExpect(status().isOk());
        verify(queries).components("ONLINE",null,"PLANNING","MODEL",null);
        mvc.perform(get("/admin/assessment/runs").requestAttr("userRole","admin").param("batchId","not-a-uuid")).andExpect(status().isBadRequest());
    }
}
