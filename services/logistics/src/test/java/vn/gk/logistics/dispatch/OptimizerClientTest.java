package vn.gk.logistics.dispatch;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.springframework.http.MediaType;

class OptimizerClientTest {
    @Test void everySolveSendsInternalTokenWithoutAddingItToTheBody() {
        var customizer=new MockServerRestTemplateCustomizer();
        var client=new OptimizerClient(new RestTemplateBuilder(customizer),"http://optimizer:8000/","optimizer-test-secret");
        MockRestServiceServer server=customizer.getServer();
        server.expect(requestTo("http://optimizer:8000/solve")).andExpect(header("X-Internal-Token","optimizer-test-secret"))
            .andExpect(content().json("{\"orders\":[]}"))
            .andRespond(withSuccess("{\"routes\":[]}",MediaType.APPLICATION_JSON));
        assertNotNull(client.solve(Map.of("orders",java.util.List.of())));server.verify();
    }
}
