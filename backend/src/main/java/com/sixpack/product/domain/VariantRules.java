package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turns an admin's description of option axes and variants into a valid {@link ProductVariants}.
 * Pure: no persistence. Violations throw {@link ApiException#badRequest} with a {@code field: reason} message.
 */
public final class VariantRules {

    public static final int MAX_AXES = 3;
    public static final int MAX_VALUES_PER_AXIS = 20;
    public static final int MAX_VARIANTS = 100;
    public static final int MAX_STOCK = 1_000_000;
    private static final int MAX_LABEL = 30;
    private static final int MAX_SKU = 60;
    private static final int MAX_IMAGES = 5;
    private static final int MAX_IMAGE_PATH = 200;
    private static final double MAX_PRICE = 10_000_000;
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    public record ValueInput(String label, String hex, List<String> images) {
    }

    public record AxisInput(String name, String type, List<ValueInput> values) {
    }

    /**
     * @param id null for a new variant; the id of a current variant to edit it in place
     * @param sku blank keeps the current SKU (existing variant) or generates one (new variant)
     * @param stock initial stock of a new variant; ignored for existing ones (stock has its own operations)
     */
    public record VariantInput(String id, String sku, List<OptionChoice> options, Double price, Double mrp,
                               Integer stock, String status) {
    }

    private VariantRules() {
    }

    /**
     * @param current the product's variants before this change (source of ids, SKUs and live stock)
     * @param skuBase prefix for generated SKUs, usually the product SKU or slug
     * @param productSku the product's own SKU, which no variant may reuse; may be null
     * @param ids generator for new variant ids
     */
    public static ProductVariants build(ProductVariants current, List<AxisInput> axisInputs,
                                        List<VariantInput> variantInputs, String skuBase, String productSku,
                                        Supplier<String> ids) {
        List<AxisInput> axesIn = axisInputs == null ? List.of() : axisInputs;
        List<VariantInput> itemsIn = variantInputs == null ? List.of() : variantInputs;
        if (axesIn.isEmpty() && itemsIn.isEmpty()) {
            return ProductVariants.EMPTY;
        }
        if (axesIn.isEmpty()) {
            throw bad("axes: define at least one option axis");
        }
        if (itemsIn.isEmpty()) {
            throw bad("variants: add at least one variant");
        }
        List<OptionAxis> axes = axes(axesIn);
        return new ProductVariants(axes, items(current, axes, itemsIn, skuBase, productSku, ids));
    }

    private static List<OptionAxis> axes(List<AxisInput> inputs) {
        if (inputs.size() > MAX_AXES) {
            throw bad("axes: at most " + MAX_AXES + " option axes");
        }
        List<OptionAxis> axes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (AxisInput in : inputs) {
            String name = text(in.name(), "axes.name", MAX_LABEL);
            if (!names.add(key(name))) {
                throw bad("axes: duplicate axis '" + name + "'");
            }
            OptionType type = OptionType.parse(in.type());
            axes.add(new OptionAxis(name, type, values(name, type, in.values())));
        }
        return axes;
    }

    private static List<OptionValue> values(String axis, OptionType type, List<ValueInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw bad("axes." + axis + ": needs at least one value");
        }
        if (inputs.size() > MAX_VALUES_PER_AXIS) {
            throw bad("axes." + axis + ": at most " + MAX_VALUES_PER_AXIS + " values");
        }
        List<OptionValue> values = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (ValueInput in : inputs) {
            String label = text(in.label(), "axes." + axis + ".label", MAX_LABEL);
            if (!labels.add(key(label))) {
                throw bad("axes." + axis + ": duplicate value '" + label + "'");
            }
            values.add(new OptionValue(label, hex(axis, type, in.hex()), images(axis, in.images())));
        }
        return values;
    }

    private static String hex(String axis, OptionType type, String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        if (type != OptionType.COLOUR || !HEX.matcher(hex.trim()).matches()) {
            throw bad("axes." + axis + ".hex: only a colour axis takes a hex like #1a2b3c");
        }
        return hex.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> images(String axis, List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > MAX_IMAGES || images.stream().anyMatch(i -> i == null || i.isBlank()
                || i.length() > MAX_IMAGE_PATH)) {
            throw bad("axes." + axis + ".images: at most " + MAX_IMAGES + " images");
        }
        return images.stream().map(String::trim).toList();
    }

    private static List<Variant> items(ProductVariants current, List<OptionAxis> axes,
                                       List<VariantInput> inputs, String skuBase, String productSku,
                                       Supplier<String> ids) {
        if (inputs.size() > MAX_VARIANTS) {
            throw bad("variants: at most " + MAX_VARIANTS + " variants");
        }
        List<Variant> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> combinations = new HashSet<>();
        Set<String> skus = new HashSet<>();
        for (VariantInput in : inputs) {
            Variant existing = null;
            if (in.id() != null && !in.id().isBlank()) {
                String id = in.id().trim();
                existing = current.find(id)
                        .orElseThrow(() -> bad("variants.id: unknown variant '" + id + "'"));
                if (!seenIds.add(id)) {
                    throw bad("variants.id: variant '" + id + "' appears more than once");
                }
            }
            List<OptionChoice> options = choices(axes, in.options());
            if (!combinations.add(options.toString().toLowerCase(Locale.ROOT))) {
                throw bad("variants: two variants have the same options (" + label(options) + ")");
            }
            double price = price(in.price());
            Double mrp = mrp(in.mrp(), price);
            String sku = sku(in.sku(), existing, skuBase, options);
            if (sku.equals(productSku)) {
                throw bad("variants.sku: '" + sku + "' is already the product's SKU");
            }
            if (!skus.add(sku)) {
                throw bad("variants.sku: duplicate sku '" + sku + "'");
            }
            int stock = existing != null ? existing.stock() : stock(in.stock());
            items.add(new Variant(existing != null ? existing.id() : newId(ids, seenIds), sku, options, price, mrp,
                    stock, VariantStatus.parse(in.status())));
        }
        return items;
    }

    /** Resolves each choice to the axis spelling and requires exactly one value for every axis. */
    private static List<OptionChoice> choices(List<OptionAxis> axes, List<OptionChoice> picked) {
        Map<String, OptionChoice> byAxis = new LinkedHashMap<>();
        for (OptionChoice choice : picked == null ? List.<OptionChoice>of() : picked) {
            OptionAxis axis = axes.stream().filter(a -> key(a.name()).equals(key(choice.name()))).findFirst()
                    .orElseThrow(() -> bad("variants.options: unknown option '" + choice.name() + "'"));
            String value = axis.values().stream().map(OptionValue::label)
                    .filter(l -> key(l).equals(key(choice.value()))).findFirst()
                    .orElseThrow(() -> bad("variants.options: '" + choice.value() + "' is not a value of "
                            + axis.name()));
            if (byAxis.put(axis.name(), new OptionChoice(axis.name(), value)) != null) {
                throw bad("variants.options: every option axis needs exactly one value");
            }
        }
        if (byAxis.size() != axes.size()) {
            throw bad("variants.options: every option axis needs exactly one value");
        }
        // axis order, regardless of the order the client sent
        return axes.stream().map(a -> byAxis.get(a.name())).toList();
    }

    private static double price(Double price) {
        if (price == null || price <= 0 || price > MAX_PRICE) {
            throw bad("variants.price: must be greater than 0");
        }
        return price;
    }

    private static Double mrp(Double mrp, double price) {
        if (mrp == null) {
            return null;
        }
        if (mrp < price || mrp > MAX_PRICE) {
            throw bad("variants.mrp: must not be less than the variant price");
        }
        return mrp;
    }

    private static int stock(Integer stock) {
        if (stock == null) {
            return 0;
        }
        if (stock < 0 || stock > MAX_STOCK) {
            throw bad("variants.stock: must be between 0 and " + MAX_STOCK);
        }
        return stock;
    }

    private static String sku(String proposed, Variant existing, String skuBase, List<OptionChoice> options) {
        String sku;
        if (proposed != null && !proposed.isBlank()) {
            sku = proposed.trim();
        } else if (existing != null) {
            sku = existing.sku();
        } else {
            sku = generatedSku(skuBase, options);
        }
        if (sku.length() > MAX_SKU) {
            throw bad("variants.sku: at most " + MAX_SKU + " characters");
        }
        return sku;
    }

    private static String generatedSku(String base, List<OptionChoice> options) {
        StringBuilder sku = new StringBuilder(base == null || base.isBlank() ? "SKU" : base.trim());
        for (OptionChoice o : options) {
            sku.append('-').append(o.value().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-")
                    .replaceAll("(^-+|-+$)", ""));
        }
        return sku.toString().toUpperCase(Locale.ROOT);
    }

    private static String newId(Supplier<String> ids, Set<String> taken) {
        String id;
        do {
            id = ids.get();
        } while (!taken.add(id));
        return id;
    }

    private static String label(List<OptionChoice> options) {
        return options.stream().map(OptionChoice::value).reduce((a, b) -> a + " / " + b).orElse("");
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw bad(field + ": is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw bad(field + ": at most " + max + " characters");
        }
        return trimmed;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest(message);
    }
}
