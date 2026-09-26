package com.devopsaaas.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestId_whenHeaderIsMissing() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest(), (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }

    @Test
    void reusesRequestId_whenHeaderIsValid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "client-trace-1234");

        MockHttpServletResponse response = run(request, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-trace-1234");
    }

    @Test
    void replacesRequestId_whenHeaderCouldInjectIntoLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abcdefgh\n{\"level\":\"ERROR\",\"message\":\"forged\"}");

        MockHttpServletResponse response = run(request, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f-]{36}")
                .doesNotContain("forged");
    }

    @Test
    void exposesRequestIdInMdcDuringRequest_andClearsItAfterwards() throws Exception {
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        MockHttpServletResponse response =
                run(new MockHttpServletRequest(), (req, res) -> seenInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seenInsideChain.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
