package com.lrj.platform.tax;

/** 请求不满足财税审查合同。 */
public class TaxValidationException extends RuntimeException {

    private final String code;

    public TaxValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
