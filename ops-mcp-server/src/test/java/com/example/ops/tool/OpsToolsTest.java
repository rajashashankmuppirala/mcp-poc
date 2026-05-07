package com.example.ops.tool;

import com.example.ops.service.OpsDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsToolsTest {

    private final OpsDataService opsDataService = mock(OpsDataService.class);
    private final OpsTools opsTools = new OpsTools(opsDataService);

    @Test
    void listFailedJobs_shouldReturnTabularData() {
        List<Map<String, Object>> mockJobs = List.of(
                Map.of("job_name", "test_job", "status", "FAILED",
                        "timestamp", "2026-05-05T10:00:00Z",
                        "error_message", "Connection timeout")
        );
        when(opsDataService.getFailedJobs(24, 1)).thenReturn(mockJobs);

        String result = opsTools.list_failed_jobs(24, null);

        assertNotNull(result);
        assertTrue(result.contains("test_job"));
        assertTrue(result.contains("FAILED"));
        verify(opsDataService).getFailedJobs(24, 1);
    }

    @Test
    void listFailedJobs_shouldDefaultTo24Hours() {
        when(opsDataService.getFailedJobs(24, 1)).thenReturn(List.of());

        String result = opsTools.list_failed_jobs(null, null);

        assertNotNull(result);
        verify(opsDataService).getFailedJobs(24, 1);
    }

    @Test
    void listFailedJobs_shouldConvertDaysToHours() {
        when(opsDataService.getFailedJobs(48, 2)).thenReturn(List.of());

        opsTools.list_failed_jobs(null, 2);

        verify(opsDataService).getFailedJobs(48, 2);
    }

    @Test
    void listSuccessfulDataflows_shouldReturnTabularData() {
        List<Map<String, Object>> mockFlows = List.of(
                Map.of("flow_name", "test_flow", "status", "SUCCESS",
                        "completed_at", "2026-05-05T10:00:00Z",
                        "records_processed", 50000)
        );
        when(opsDataService.getSuccessfulDataflows(24, 1)).thenReturn(mockFlows);

        String result = opsTools.list_successful_dataflows(24, null);

        assertNotNull(result);
        assertTrue(result.contains("test_flow"));
        assertTrue(result.contains("SUCCESS"));
        verify(opsDataService).getSuccessfulDataflows(24, 1);
    }

    @Test
    void listSuccessfulDataflows_shouldDefaultTo24Hours() {
        when(opsDataService.getSuccessfulDataflows(24, 1)).thenReturn(List.of());

        String result = opsTools.list_successful_dataflows(null, null);

        assertNotNull(result);
        assertEquals("[]", result);
    }

    @Test
    void listSuccessfulDataflows_shouldConvertDaysToHours() {
        when(opsDataService.getSuccessfulDataflows(72, 3)).thenReturn(List.of());

        opsTools.list_successful_dataflows(null, 3);

        verify(opsDataService).getSuccessfulDataflows(72, 3);
    }
}
