package com.sixpack.marketing;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Reads a product feed back for assertions. */
public final class FeedXml {

    private FeedXml() {
    }

    /** Each {@code <item>} as tag name (with prefix, e.g. {@code g:id}) → text, in document order. */
    public static List<Map<String, String>> items(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml))
                .getElementsByTagName("item");
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Map<String, String> item = new LinkedHashMap<>();
            NodeList fields = nodes.item(i).getChildNodes();
            for (int j = 0; j < fields.getLength(); j++) {
                if (fields.item(j) instanceof Element field) {
                    item.put(field.getTagName(), field.getTextContent());
                }
            }
            items.add(item);
        }
        return items;
    }
}
