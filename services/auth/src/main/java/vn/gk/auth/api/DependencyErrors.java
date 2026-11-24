package vn.gk.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import java.util.Map;

@RestControllerAdvice
public class DependencyErrors {
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<?> unavailable(RestClientException error) {
        return ResponseEntity.status(503).body(Map.of("message", "Dịch vụ hồ sơ tài xế tạm thời không khả dụng"));
    }
}
