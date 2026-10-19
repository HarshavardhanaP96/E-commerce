package com.sixpack.payment.infra;

import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentGateways;
import com.sixpack.payment.PaymentProperties;
import com.sixpack.payment.infra.fake.FakeGateways;
import com.sixpack.payment.infra.razorpay.RazorpayGateways;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantSettings;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Chooses the gateway for the current tenant. A tenant with its own payment settings gets a gateway built from
 * its keys; the secret is read from the environment entry named by {@code secretRef}
 * ({@code TENANT_SECRET_<REF>}), never from the database. Only the default tenant may fall back to the platform's
 * gateway, so another store's money can never flow into the platform's account by accident.
 */
@Component
class TenantPaymentGateways implements PaymentGateways {

    static final String NOT_CONFIGURED = "Payments are not configured for this store";

    private final PaymentGateway platformGateway;
    private final TenantSettings tenant;
    private final PaymentProperties props;
    private final Environment env;
    private final ObjectProvider<RestClient.Builder> clients;
    private final Map<String, PaymentGateway> byTenant = new ConcurrentHashMap<>();

    TenantPaymentGateways(PaymentGateway platformGateway, TenantSettings tenant, PaymentProperties props,
                          Environment env, ObjectProvider<RestClient.Builder> clients) {
        this.platformGateway = platformGateway;
        this.tenant = tenant;
        this.props = props;
        this.env = env;
        this.clients = clients;
    }

    @Override
    public PaymentGateway current() {
        PaymentSettings settings = tenant.payment();
        if (settings.configured()) {
            String key = TenantContext.require().id() + "|" + settings.provider() + "|" + settings.keyId() + "|"
                    + settings.secretRef();
            return byTenant.computeIfAbsent(key, k -> build(settings));
        }
        if (tenant.isDefaultTenant()) {
            return platformGateway;
        }
        throw notConfigured();
    }

    private PaymentGateway build(PaymentSettings settings) {
        if ("fake".equals(settings.provider())) {
            return FakeGateways.create(env);
        }
        if (!"razorpay".equals(settings.provider())) {
            throw notConfigured();
        }
        String secret = env.getProperty("TENANT_SECRET_" + settings.secretRef().toUpperCase(Locale.ROOT));
        if (secret == null || secret.isBlank()) {
            throw notConfigured();
        }
        return RazorpayGateways.create(settings.keyId(), secret, props.razorpay().baseUrl(), clients.getObject());
    }

    private static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
    }
}
