package vn.gk.logistics.dispatch;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** The only client for both manual distribution and background optimization. */
@Component
public class OptimizerClient {
    private final RestTemplate rest;
    private final String url;
    private final String token;
    public OptimizerClient(RestTemplateBuilder builder, @Value("${app.optimizer-url}") String url,
                           @Value("${app.internal-token}") String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN must not be blank");
        this.rest=builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(100)).build();
        this.url=url.replaceAll("/+$", ""); this.token=token;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> solve(Object request) {
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token",token);
        return rest.postForObject(url+"/solve",new HttpEntity<>(request,headers),Map.class);
    }
}
