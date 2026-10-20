package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.StoreConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who the default store's invoices say sold the goods, for any detail that store has not set itself. Other stores
 * never fall back to these: their invoices must name them, not the platform. {@code sellerState} decides CGST/SGST
 * versus IGST, so set it to the state you are registered in, spelled as customers type it ("Karnataka", not "KA").
 */
@ConfigurationProperties("app.invoice")
public record InvoiceProperties(String sellerName, String sellerAddress, String sellerGstin, String sellerState,
                                String sellerEmail, String sellerPhone) {

    public InvoiceProperties {
        sellerName = sellerName == null || sellerName.isBlank() ? "6Pack" : sellerName;
    }

    StoreConfig.Seller seller() {
        return new StoreConfig.Seller(sellerName, sellerAddress, sellerGstin, sellerState, sellerEmail, sellerPhone);
    }
}
