package com.example.mcp.tool;

import com.example.mcp.service.ReportStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReportToolsTest {

    @Autowired
    private ReportTools reportTools;

    @MockitoBean
    private ReportStreamService reportStreamService;

    @Test
    void generateReport_shouldStreamData() {
        when(reportStreamService.streamReport(any(), anyString()))
                .thenReturn(Flux.just("row1,data", "row2,data"));

        var result = reportTools.generate_report("revenue", null, null, null);

        assert result != null;
        assert result.size() == 2;
        assert result.get(0).equals("row1,data");
    }
}
