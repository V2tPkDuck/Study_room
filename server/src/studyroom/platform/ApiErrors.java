package studyroom.platform;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Maps exceptions to the stable JSON error shape: {@code {"message": "..."}}. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException ex) { return error(ex.status(), ex.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        return error(400, ex.getBindingResult().getFieldErrors().stream().findFirst()
            .map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("请求参数无效"));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception ex) { return error(400, "请求格式或参数无效"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException ex) { return error(409, "名称或记录重复，或存在关联数据，请检查后重试"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        LoggerFactory.getLogger(ApiErrors.class).error("API failure", ex);
        return error(500, "服务暂时不可用，请查看服务器日志");
    }
    private ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).body(Map.of("message", message)); }
}
