package com.sixpack.payment.infra.fake;

import com.sixpack.payment.PaymentGateway;
import org.springframework.core.env.Environment;

/** Builds a fake gateway for a tenant. Like the platform one, it refuses to exist under the prod profile. */
public final class FakeGateways {

    private FakeGateways() {
    }

    public static PaymentGateway create(Environment env) {
        return new FakeGateway(env);
    }
}
