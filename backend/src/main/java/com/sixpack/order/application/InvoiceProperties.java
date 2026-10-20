package com.sixpack.order.application;

import com.sixpack.order.domain.Invoice;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who the invoice says sold the goods. {@code sellerState} decides CGST/SGST versus IGST, so set it to the state
 * you are registered in, spelled as customers type it ("Karnataka", not "KA").
 */
@ConfigurationProperties("app.invoice")
public record InvoiceProperties(String sellerName, String sellerAddress, String sellerGstin, String sellerState,
                                String sellerEmail, String sellerPhone) {

    public InvoiceProperties {
        sellerName = sellerName == null || sellerName.isBlank() ? "6Pack" : sellerName;
    }

    Invoice.Seller seller() {
        return new Invoice.Seller(sellerName, sellerAddress, sellerGstin, sellerState, sellerEmail, sellerPhone);
    }
}
