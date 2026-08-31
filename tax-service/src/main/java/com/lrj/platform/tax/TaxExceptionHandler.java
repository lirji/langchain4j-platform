package com.lrj.platform.tax;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把结构校验错误映射为稳定的 400 JSON 合同。 */
@RestControllerAdvice
public class TaxExceptionHandler {

    @ExceptionHandler(TaxValidationException.class)
    public ResponseEntity<TaxErrorResponse> validation(TaxValidationException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new TaxErrorResponse(error.code(), error.getMessage()));
    }

    public record TaxErrorResponse(String code, String message) {
    }
}
