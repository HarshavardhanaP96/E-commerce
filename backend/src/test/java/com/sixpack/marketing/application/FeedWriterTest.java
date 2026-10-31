package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.sixpack.marketing.FeedXml;
import com.sixpack.product.CatalogReads.Listing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class FeedWriterTest {

    private static final String BASE = "https://shop.acme.test";
    private static final String PID = "65f000000000000000000001";

    private record Written(byte[] xml, FeedWriter writer) {
    }

    private static Written write(Listing... listings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FeedWriter writer = new FeedWriter(out, "Acme Shop", BASE, "USD");
        for (Listing l : listings) {
            writer.add(l);
        }
        writer.finish();
        return new Written(out.toByteArray(), writer);
    }

    private static List<Map<String, String>> items(Listing... listings) throws Exception {
        return FeedXml.items(write(listings).xml());
    }

    private static Listing product(String name, String description, String photo, double price, int stock) {
        return new Listing(PID, null, name, null, description, null, photo, price, stock);
    }

    @Test
    void writesAGoogleShoppingRssChannel() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element rss = factory.newDocumentBuilder().parse(new ByteArrayInputStream(write().xml()))
                .getDocumentElement();

        assertThat(rss.getTagName()).isEqualTo("rss");
        assertThat(rss.getAttribute("version")).isEqualTo("2.0");
        assertThat(rss.lookupNamespaceURI("g")).isEqualTo("http://base.google.com/ns/1.0");
        Element channel = (Element) rss.getElementsByTagName("channel").item(0);
        assertThat(channel.getElementsByTagName("title").item(0).getTextContent()).isEqualTo("Acme Shop");
        assertThat(channel.getElementsByTagName("link").item(0).getTextContent()).isEqualTo(BASE);
        assertThat(channel.getElementsByTagName("description").item(0).getTextContent())
                .isEqualTo("Products from Acme Shop");
    }

    @Test
    void aProductWithoutVariantsIsOneItem() throws Exception {
        assertThat(items(product("Mug", "A mug.", "uploads/p/mug.png", 250, 4)).get(0)).containsExactly(
                entry("g:id", PID), entry("title", "Mug"), entry("description", "A mug."),
                entry("link", BASE + "/product/" + PID), entry("g:image_link", BASE + "/uploads/p/mug.png"),
                entry("g:availability", "in stock"), entry("g:price", "250.00 USD"),
                entry("g:brand", "Acme Shop"), entry("g:condition", "new"));
    }

    @Test
    void theBrandIsTheProductsOwnElseTheStoreName() throws Exception {
        assertThat(items(new Listing(PID, null, "Mug", null, null, "Potter & Co", "uploads/p/a.png", 1, 1),
                new Listing(PID, null, "Bowl", null, null, null, "uploads/p/b.png", 1, 1),
                new Listing(PID, null, "Cup", null, null, "  ", "uploads/p/c.png", 1, 1)))
                .extracting(i -> i.get("g:brand")).containsExactly("Potter & Co", "Acme Shop", "Acme Shop");
    }

    @Test
    void variantsAreItemsGroupedUnderTheirProduct() throws Exception {
        List<Map<String, String>> items = items(
                new Listing(PID, "tee-s", "Tee", "S / Black", null, null, "uploads/p/s.png", 499, 5),
                new Listing(PID, "tee-m", "Tee", "M / Black", null, null, "uploads/p/m.png", 549, 0));

        assertThat(items).extracting(i -> i.get("g:id")).containsExactly(PID + "-tee-s", PID + "-tee-m");
        assertThat(items).extracting(i -> i.get("g:item_group_id")).containsOnly(PID);
        assertThat(items).extracting(i -> i.get("title")).containsExactly("Tee - S / Black", "Tee - M / Black");
        assertThat(items).extracting(i -> i.get("g:availability")).containsExactly("in stock", "out of stock");
        assertThat(items).extracting(i -> i.get("description")).containsOnly("Tee");
        assertThat(items(product("Mug", null, "uploads/p/mug.png", 1, 1)).get(0))
                .doesNotContainKey("g:item_group_id");
    }

    @Test
    void pricesHaveTwoDecimalsAndTheStoresCurrency() throws Exception {
        assertThat(items(product("A", null, "uploads/a.png", 0.1 + 0.2, 1),
                product("B", null, "uploads/b.png", 1234.5, 1), product("C", null, "uploads/c.png", 7, 1)))
                .extracting(i -> i.get("g:price")).containsExactly("0.30 USD", "1234.50 USD", "7.00 USD");
    }

    @Test
    void htmlIsStrippedFromDescriptions() throws Exception {
        assertThat(items(product("Mug", "<p>Holds <b>tea</b> &amp; coffee.</p>\n<br/>Dishwasher&nbsp;safe",
                "uploads/p/mug.png", 1, 1)).get(0).get("description"))
                .isEqualTo("Holds tea & coffee. Dishwasher safe");
    }

    @Test
    void markupInTextIsEscapedAndCharactersXmlCannotCarryAreDropped() throws Exception {
        byte[] xml = write(product("Tom & Jerry <\"Mugs\">\u0001￾", "a\u0000b", "uploads/p/tj.png", 1, 1)).xml();

        assertThat(new String(xml, StandardCharsets.UTF_8)).contains("Tom &amp; Jerry &lt;")
                .doesNotContain("\u0001").doesNotContain("\u0000");
        Map<String, String> item = FeedXml.items(xml).get(0);
        assertThat(item.get("title")).isEqualTo("Tom & Jerry <\"Mugs\">");
        assertThat(item.get("description")).isEqualTo("ab");
    }

    @Test
    void imageAddressesAreAbsoluteOnTheStoresDomain() throws Exception {
        assertThat(items(product("A", null, "uploads/a.png", 1, 1), product("B", null, "/uploads/b.png", 1, 1)))
                .extracting(i -> i.get("g:image_link"))
                .containsExactly(BASE + "/uploads/a.png", BASE + "/uploads/b.png");
    }

    @Test
    void listingsWithoutAnImageAreLeftOutAndCounted() throws Exception {
        Written w = write(product("A", null, null, 1, 1), product("B", null, " ", 1, 1),
                product("C", null, "uploads/c.png", 1, 1));

        assertThat(FeedXml.items(w.xml())).extracting(i -> i.get("title")).containsExactly("C");
        assertThat(w.writer().items()).isEqualTo(1);
        assertThat(w.writer().skipped()).isEqualTo(2);
    }

    @Test
    void valuesStayWithinGooglesLimits() throws Exception {
        Listing longOne = new Listing(PID, "v".repeat(40), "T".repeat(200), null, "d".repeat(6000), null,
                "uploads/p.png", 1, 1);
        Listing other = new Listing(PID, "w".repeat(40), "T", null, null, null, "uploads/p.png", 1, 1);

        Map<String, String> item = items(longOne).get(0);
        assertThat(item.get("g:id")).startsWith(PID + "-").hasSizeLessThanOrEqualTo(50)
                .isEqualTo(items(longOne).get(0).get("g:id"))
                .isNotEqualTo(items(other).get(0).get("g:id"));
        assertThat(item.get("title")).hasSize(150).endsWith("…");
        assertThat(item.get("description")).hasSize(5000).endsWith("…");
    }
}
