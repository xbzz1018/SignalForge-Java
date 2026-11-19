package com.signalforge.finance;

import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.infrastructure.PublicDataHttp;
import com.signalforge.research.ResearchRequest.Security;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EtfPriceSourceTest {
    @ParameterizedTest
    @ValueSource(strings = {"510300", "510500", ""})
    void fallbackVerifiesReturnedSecurity(String returnedCode) throws Exception {
        var http = mock(PublicDataHttp.class);
        var response = new JsonCodec().tree("""
                {"code":0,"data":{"sh510300":{"qt":{"sh510300":["1","测试ETF","%s"]},
                "day":[["2025-01-02","1","2"]]}}}
                """.formatted(returnedCode));
        when(http.json(any())).thenThrow(new IOException("primary unavailable")).thenReturn(response);
        var source = new EtfPriceSource(http);
        var security = new Security("510300", "SSE");
        var end = LocalDate.of(2025, 1, 2);
        if (returnedCode.equals(security.securityId())) {
            assertEquals("tencent", source.load(security, end).provider());
        } else {
            assertEquals("ETF_IDENTITY_MISMATCH", assertThrows(IllegalArgumentException.class,
                    () -> source.load(security, end)).getMessage());
        }
    }
}
