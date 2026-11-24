package vn.gk.logistics.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(vn.gk.logistics.validation.OrderInputs.Invalid.class)
    public ResponseEntity<?> fields(vn.gk.logistics.validation.OrderInputs.Invalid e) {return ResponseEntity.badRequest().body(Map.of("message",e.getMessage(),"fields",e.fields));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> malformed(Exception e) {return ResponseEntity.badRequest().body(Map.of("message","Kiểm tra kiểu dữ liệu, ngày giờ và các trường bắt buộc."));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.format.DateTimeParseException.class})
    public ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()==null?"Thông tin không hợp lệ":error.getMessage()));
    }
    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    public ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("message","Dữ liệu không còn tồn tại hoặc chuyến đã được thu hồi. Hãy tải lại.")); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("message","Thông tin bị trùng hoặc đang được sử dụng. Hãy tải lại và kiểm tra hồ sơ liên quan."));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> state(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"Không thể thực hiện thao tác":error.getReason()));
    }
}
