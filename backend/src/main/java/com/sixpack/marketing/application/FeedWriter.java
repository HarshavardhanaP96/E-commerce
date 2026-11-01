package com.sixpack.marketing.application;

import com.sixpack.product.CatalogReads.Listing;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes a Google product feed (RSS 2.0 with the {@code g:} namespace), which Google Merchant Center and the Meta
 * catalogue both read. Items are written as they arrive, so the catalogue is never held as a whole. The XML writer
 * escapes all text; characters XML 1.0 cannot carry are dropped first.
 */
final class FeedWriter {

    static final String G = "http://base.google.com/ns/1.0";
    // Google's limits: longer values get the item rejected.
    private static final int MAX_ID = 50;
    private static final int MAX_TITLE = 150;
    private static final int MAX_DESCRIPTION = 5000;
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NOT_XML =
            Pattern.compile("[^\\x09\\x0A\\x0D\\x20-\\x{D7FF}\\x{E000}-\\x{FFFD}\\x{10000}-\\x{10FFFF}]");

    private final XMLStreamWriter xml;
    private final String base;
    private final String storeName;
    private final String currency;
    private int items;
    private int skipped;

    /** @param base the store's address without a trailing slash, e.g. {@code https://shop.acme.test} */
    FeedWriter(OutputStream out, String storeName, String base, String currency) {
        this.base = base;
        this.storeName = storeName;
        this.currency = currency;
        try {
            xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeNamespace("g", G);
            xml.writeStartElement("channel");
            element("title", storeName);
            element("link", base);
            element("description", "Products from " + storeName);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not start the product feed", e);
        }
    }

    /** Adds the listing as one item, or counts it as skipped when it has no image (both platforms require one). */
    void add(Listing l) {
        if (l.photo() == null || l.photo().isBlank()) {
            skipped++;
            return;
        }
        String description = plain(l.description());
        try {
            xml.writeStartElement("item");
            g("id", id(l));
            if (l.variantId() != null) {
                g("item_group_id", l.productId());
            }
            element("title", cut(l.variantLabel() == null ? l.name() : l.name() + " - " + l.variantLabel(),
                    MAX_TITLE));
            element("description", cut(description.isEmpty() ? l.name() : description, MAX_DESCRIPTION));
            element("link", base + "/product/" + l.productId());
            g("image_link", base + "/" + l.photo().replaceFirst("^/+", ""));
            g("availability", l.stock() > 0 ? "in stock" : "out of stock");
            g("price", BigDecimal.valueOf(l.price()).setScale(2, RoundingMode.HALF_UP).toPlainString() + " "
                    + currency);
            g("brand", l.brand() == null || l.brand().isBlank() ? storeName : l.brand());
            g("condition", "new");
            xml.writeEndElement();
            items++;
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not write product " + l.productId() + " to the feed", e);
        }
    }

    void finish() {
        try {
            xml.writeEndElement(); // channel
            xml.writeEndElement(); // rss
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not finish the product feed", e);
        }
    }

    int items() {
        return items;
    }

    int skipped() {
        return skipped;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Product id, or product id + variant id; the variant part is hashed when the whole would pass 50. */
    private static String id(Listing l) {
        if (l.variantId() == null) {
            return l.productId();
        }
        String id = l.productId() + "-" + l.variantId();
        return id.length() <= MAX_ID ? id
                : l.productId() + "-" + sha256(l.variantId().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String plain(String html) {
        if (html == null) {
            return "";
        }
        String text = TAG.matcher(html).replaceAll(" ").replace("&nbsp;", " ").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return SPACE.matcher(text).replaceAll(" ").trim();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void element(String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(clean(text));
        xml.writeEndElement();
    }

    private void g(String name, String text) throws XMLStreamException {
        xml.writeStartElement("g", name, G);
        xml.writeCharacters(clean(text));
        xml.writeEndElement();
    }

    /** Also removes a half surrogate pair left by {@link #cut}. */
    private static String clean(String text) {
        return NOT_XML.matcher(text).replaceAll("");
    }
}
