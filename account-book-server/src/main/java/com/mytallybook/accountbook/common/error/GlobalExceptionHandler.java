package com.mytallybook.accountbook.common.error;

import com.mytallybook.accountbook.common.api.ApiError;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return errorResponse(
                exception.errorCode(),
                exception.getMessage(),
                exception.details(),
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return errorResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                details,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableMessage(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            ServletRequestBindingException.class
    })
    ResponseEntity<ApiError> handleRequestParameterValidation(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler({
            DataIntegrityViolationException.class,
            OptimisticLockingFailureException.class
            , org.springframework.dao.PessimisticLockingFailureException.class
    })
    ResponseEntity<ApiError> handlePersistenceConflict(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.CONFLICT,
                ErrorCode.CONFLICT.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.ACCESS_DENIED,
                ErrorCode.ACCESS_DENIED.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleResourceNotFound(HttpServletRequest request) {
        return errorResponse(
                ErrorCode.RESOURCE_NOT_FOUND,
                ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(),
                null,
                request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled request failure, requestId={}, method={}, path={}, exceptionType={}, location={}",
                RequestIdFilter.getRequestId(request),
                request.getMethod(),
                handlerPattern(request),
                exception.getClass().getName(),
                SafeExceptionLocation.firstApplicationFrame(exception)
            );
        return errorResponse(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                null,
                request
        );
    }

    private ResponseEntity<ApiError> errorResponse(
            ErrorCode errorCode,
            String message,
            Object details,
            HttpServletRequest request
    ) {
        ApiError body = ApiError.of(
                errorCode.name(),
                message,
                details,
                RequestIdFilter.getRequestId(request)
        );
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    private String handlerPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value ? value : "<unmapped>";
    }
}
