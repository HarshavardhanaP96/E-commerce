package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Sections;
import com.sixpack.dashboard.domain.Sections.CategoryShare;
import com.sixpack.dashboard.domain.Sections.CouponUse;
import com.sixpack.dashboard.domain.Sections.CustomerTotals;
import com.sixpack.dashboard.domain.Sections.IdleProduct;
import com.sixpack.dashboard.domain.Sections.MethodShare;
import com.sixpack.dashboard.domain.Sections.ProductSales;
import com.sixpack.dashboard.domain.Sections.RefundPoint;
import com.sixpack.dashboard.domain.Sections.StockLeft;
import com.sixpack.dashboard.domain.Sections.TaxRow;
import com.sixpack.dashboard.domain.SectionsReadPort;
import com.sixpack.dashboard.domain.SectionsReadPort.MethodTotals;
import com.sixpack.dashboard.domain.SectionsReadPort.RefundIssued;
import com.sixpack.dashboard.domain.SectionsReadPort.TaxGroup;
import com.sixpack.product.LowStockLine;
import com.sixpack.product.ProductCatalog;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The shop dashboard's sections (analytics step 3), read live for the period in the store's time zone. With
 * {@code limit} > 0 the ranked lists (top products, top customers, places) are cut to that many rows; 0 keeps every
 * row, for the CSV exports.
 */
@Service
public class SectionsService {

    /** The rate assumed for order lines saved before each line carried the rate it was charged at. */
    private static final double LEGACY_TAX_RATE = 18.0;
    /** Places shown on the page; the export has them all. */
    private static final int PLACES_SHOWN = 50;

    private final SectionsReadPort read;
    private final ProductCatalog catalog;
    private final StoreConfig store;

    SectionsService(SectionsReadPort read, ProductCatalog catalog, StoreConfig store) {
        this.read = read;
        this.catalog = catalog;
        this.store = store;
    }

    public ZoneId zone() {
        return ZoneId.of(store.effective().timeZone());
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'products:' + #root.target.zone() + #period + #limit")
    public Sections.Products products(Period period, int limit) {
        ZoneId zone = zone();
        Instant from = period.start(zone);
        Instant to = period.end(zone);
        List<ProductSales> sales = read.productSales(from, to);

        Map<String, double[]> byCategory = read.categorySales(from, to);
        double total = byCategory.values().stream().mapToDouble(v -> v[0]).sum();
        List<CategoryShare> categories = byCategory.entrySet().stream()
                .map(e -> new CategoryShare(e.getKey(), e.getValue()[0], e.getValue()[1],
                        Sections.percent(e.getValue()[0], total)))
                .toList();

        Map<String, Double> units = read.unitsByLine(from, to);
        List<StockLeft> stock = new ArrayList<>();
        for (LowStockLine line : catalog.lowStock()) {
            String key = line.productId() + (line.variantId() == null ? "" : ":" + line.variantId());
            double sold = units.getOrDefault(key, 0.0);
            Integer daysLeft = line.stock() <= 0 ? Integer.valueOf(0)
                    : sold == 0 ? null : (int) Math.floor(line.stock() * period.days() / sold);
            stock.add(new StockLeft(line.productId(), line.name(), line.variantId(), line.label(), line.stock(),
                    line.threshold(), sold, daysLeft));
        }

        Set<String> sold = sales.stream().map(ProductSales::productId).collect(Collectors.toSet());
        List<IdleProduct> idle = read.onSale(to).stream().filter(p -> !sold.contains(p.productId()))
                .map(p -> new IdleProduct(p.productId(), p.name(), p.stock())).toList();
        return new Sections.Products(cut(sales, limit), categories, stock, idle);
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'customers:' + #root.target.zone() + #period + #limit")
    public Sections.Customers customers(Period period, int limit) {
        ZoneId zone = zone();
        Instant from = period.start(zone);
        Instant to = period.end(zone);
        List<CustomerTotals> buyers = read.customerTotals(from, to);
        long returning = read.orderedBefore(buyers.stream().map(CustomerTotals::userId).toList(), from).size();
        long repeat = buyers.stream().filter(b -> b.orders() >= 2).count();
        return new Sections.Customers(buyers.size(), buyers.size() - returning, returning,
                Sections.percent(repeat, buyers.size()), cut(buyers, limit),
                cut(read.places(from, to), limit == 0 ? 0 : PLACES_SHOWN));
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'coupons:' + #root.target.zone() + #period")
    public Sections.Coupons coupons(Period period) {
        ZoneId zone = zone();
        Instant from = period.start(zone);
        Instant to = period.end(zone);
        List<CouponUse> codes = read.couponUses(from, to);
        long withCoupon = codes.stream().mapToLong(CouponUse::uses).sum();
        return new Sections.Coupons(withCoupon, Sections.percent(withCoupon, read.orders(from, to)),
                codes.stream().mapToDouble(CouponUse::discount).sum(), codes);
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'payments:' + #root.target.zone() + #period")
    public Sections.Payments payments(Period period) {
        ZoneId zone = zone();
        Instant from = period.start(zone);
        Instant to = period.end(zone);
        List<MethodTotals> methods = read.paymentMethods(from, to);
        long orders = methods.stream().mapToLong(MethodTotals::orders).sum();
        List<MethodShare> mix = methods.stream()
                .map(m -> new MethodShare(m.method(), m.orders(), m.amount(), Sections.percent(m.orders(), orders)))
                .toList();

        Granularity g = Granularity.auto(period);
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        Map<LocalDate, Double> amounts = new LinkedHashMap<>();
        for (LocalDate start : g.buckets(period)) {
            counts.put(start, new long[1]);
            amounts.put(start, 0.0);
        }
        for (RefundIssued r : read.refunds(from, to, zone)) {
            LocalDate start = g.bucketStart(r.day());
            if (counts.containsKey(start)) {
                counts.get(start)[0]++;
                amounts.merge(start, r.amount(), Double::sum);
            }
        }
        List<RefundPoint> refunds = counts.keySet().stream()
                .map(start -> new RefundPoint(start, counts.get(start)[0], amounts.get(start))).toList();
        return new Sections.Payments(g.name().toLowerCase(Locale.ROOT), mix, refunds);
    }

    /**
     * GST on orders placed in the period, per local month and rate. Each order's tax at a rate is rounded to whole
     * rupees as checkout charged it; within the seller's state it is split half CGST, half SGST, elsewhere IGST,
     * as on the invoices. Refunds are not taken off (no credit notes yet).
     */
    @Cacheable(cacheNames = DASHBOARD, key = "'tax:' + #root.target.zone() + #period")
    public Sections.Tax tax(Period period) {
        ZoneId zone = zone();
        String sellerState = store.seller().state();
        Map<String, double[]> rows = new TreeMap<>(); // "month|rate" -> taxable, cgst, sgst, igst, tax
        for (TaxGroup group : read.taxGroups(period.start(zone), period.end(zone), zone)) {
            double rate = group.rate() == null ? LEGACY_TAX_RATE : group.rate();
            long paise = Math.round(group.taxableValue() * 100);
            double tax = Math.round(paise * (rate / 100.0) / 100.0);
            double[] row = rows.computeIfAbsent(group.month() + "|" + String.format(Locale.ROOT, "%08.3f", rate),
                    k -> new double[5]);
            row[0] += paise / 100.0;
            if (sameState(sellerState, group.state())) {
                row[1] += tax / 2;
                row[2] += tax / 2;
            } else {
                row[3] += tax;
            }
            row[4] += tax;
        }
        List<TaxRow> out = rows.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\\|");
            double[] v = e.getValue();
            return new TaxRow(key[0], Double.parseDouble(key[1]), v[0], v[1], v[2], v[3], v[4]);
        }).toList();
        return new Sections.Tax(sellerState, out, out.stream().mapToDouble(TaxRow::taxableValue).sum(),
                out.stream().mapToDouble(TaxRow::tax).sum());
    }

    private static boolean sameState(String a, String b) {
        return a != null && b != null && !a.isBlank()
                && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private static <T> List<T> cut(List<T> rows, int limit) {
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows;
    }
}
