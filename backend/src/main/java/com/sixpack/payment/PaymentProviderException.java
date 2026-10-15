package com.sixpack.payment;

import com.sixpack.shared.ApiException;
import org.springframework.http.HttpStatus;

public class PaymentProviderException extends ApiException {

    public PaymentProviderException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }
}
