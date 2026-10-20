package com.sixpack.payment;

/** Public API of the payment module: the gateway to use for the tenant in the current request. */
public interface PaymentGateways {

    /**
     * The tenant's own payment account, or, for the default tenant only, the platform gateway. Never routes one
     * store's payments through another store's (or the platform's) account.
     *
     * @throws com.sixpack.shared.ApiException 503 if the tenant has no usable payment account
     */
    PaymentGateway current();
}
