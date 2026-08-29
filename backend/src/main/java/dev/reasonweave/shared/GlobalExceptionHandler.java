package dev.reasonweave.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
            .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求字段校验失败", fields, request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> handleMissingRequestValue(Exception exception, HttpServletRequest request) {
        String name = exception instanceof MissingServletRequestParameterException parameter
            ? parameter.getParameterName()
            : ((MissingRequestHeaderException) exception).getHeaderName();
        return error(
            HttpStatus.BAD_REQUEST,
            "MISSING_REQUEST_VALUE",
            "请求缺少必要参数或请求头",
            Map.of("name", name),
            request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(
        MethodArgumentTypeMismatchException exception,
        HttpServletRequest request
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", exception.getName());
        details.put("value", String.valueOf(exception.getValue()));
        if (exception.getRequiredType() != null) {
            details.put("required_type", exception.getRequiredType().getSimpleName());
        }
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_VALUE", "请求参数格式无效", details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "请求体不是有效的 JSON", Map.of(), request);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> handleMethodValidation(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", Map.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> handleNotFound(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "请求的资源不存在", Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(
        HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request
    ) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "当前接口不支持该请求方法", Map.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaType(
        HttpMediaTypeNotSupportedException exception,
        HttpServletRequest request
    ) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不受支持", Map.of(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "资源已存在或违反唯一约束", Map.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUpload(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传文件超过 20MB 限制", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = RequestIds.current(request);
        log.error("Unhandled request failure request_id={}", requestId, exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", Map.of(), request);
    }

    private ResponseEntity<ApiError> error(
        HttpStatus status,
        String code,
        String message,
        Map<String, Object> details,
        HttpServletRequest request
    ) {
        String requestId = RequestIds.current(request);
        ApiError body = new ApiError(
            new ApiError.ErrorBody(code, message, details),
            new ApiEnvelope.Meta(requestId, null)
        );
        return ResponseEntity.status(status).body(body);
    }
}
