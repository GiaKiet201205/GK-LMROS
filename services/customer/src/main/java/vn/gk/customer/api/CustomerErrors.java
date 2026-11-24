package vn.gk.customer.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class CustomerErrors {
    @ExceptionHandler(vn.gk.customer.validation.OrderInputs.Invalid.class)
    public ResponseEntity<?> fields(vn.gk.customer.validation.OrderInputs.Invalid e) {return ResponseEntity.badRequest().body(Map.of("message",e.getMessage(),"fields",e.fields));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> malformed(Exception e) {return ResponseEntity.badRequest().body(Map.of("message","Kiểm tra kiểu dữ liệu, ngày giờ và các trường bắt buộc."));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.format.DateTimeParseException.class})
    public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"Thông tin không hợp lệ":e.getMessage())); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> state(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Không thể thực hiện thao tác":e.getReason())); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(Exception e) {return ResponseEntity.status(409).body(Map.of("message","Thông tin tài khoản vừa thay đổi hoặc đã được sử dụng. Hãy thử lại hoặc đăng nhập tài khoản hiện có."));}
    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    public ResponseEntity<?> unavailable(Exception e) { return ResponseEntity.status(503).body(Map.of("message","Chưa tìm được địa chỉ lúc này. Thử lại hoặc dán tọa độ/liên kết Google Maps đầy đủ để xác nhận vị trí.")); }
}
