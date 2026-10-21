package com.sixpack.dashboard.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which items of the audience table to hide. An item is a gender × band cell, a row total, a column total
 * or the grand total; {@code buyers[gender][band]} holds the true buyers, with index {@code genders} for the total
 * row and {@code bands} for the total column.
 *
 * <ol>
 *   <li>Primary: every item with fewer than {@code min} buyers, except the grand total (public anyway: it is the
 *       Customers count).</li>
 *   <li>Per sum, until nothing changes (the sums: each gender row and the all row against their totals, each band
 *       column and the total column against theirs): if the sum's hidden parts hold 1 to min−1 buyers, its total
 *       minus its shown parts would give that group away, so its shown part with the fewest buyers is hidden too
 *       (the first in gender and band order on a tie). The total counts as known even when it is hidden, since the
 *       other direction may give it away.</li>
 *   <li>Audit: the shown figures and the sums are linear equations over the hidden items. Every group of hidden
 *       items whose total those equations fix exactly is found (the 0/1 vectors in their row space); while one has
 *       1 to min−1 buyers and is not the whole table, one more shown item is hidden — the one with the fewest buyers
 *       that breaks it — and step 2 runs again.</li>
 * </ol>
 * Buyers drive every choice; orders and revenue sit in the same equations, so hiding an item hides all of them.
 */
final class AudienceSuppression {

    private static final double EPS = 1e-6;

    private final double[][] buyers;
    private final int rows;
    private final int cols;
    private final int min;
    private final int grand;
    /** Each sum as item ids: the total first, then the parts. */
    private final List<int[]> sums = new ArrayList<>();
    private final boolean[] hidden;

    private AudienceSuppression(double[][] buyers, int min) {
        this.buyers = buyers;
        this.rows = buyers.length;
        this.cols = buyers[0].length;
        this.min = min;
        this.grand = id(rows - 1, cols - 1);
        for (int g = 0; g < rows; g++) {
            int[] sum = new int[cols];
            sum[0] = id(g, cols - 1);
            for (int b = 0; b < cols - 1; b++) {
                sum[b + 1] = id(g, b);
            }
            sums.add(sum);
        }
        for (int b = 0; b < cols; b++) {
            int[] sum = new int[rows];
            sum[0] = id(rows - 1, b);
            for (int g = 0; g < rows - 1; g++) {
                sum[g + 1] = id(g, b);
            }
            sums.add(sum);
        }
        hidden = new boolean[rows * cols];
        for (int i = 0; i < hidden.length; i++) {
            hidden[i] = i != grand && buyers(i) < min;
        }
    }

    /** @return hidden[gender][band], same shape as {@code buyers} */
    static boolean[][] hide(double[][] buyers, int min) {
        AudienceSuppression s = new AudienceSuppression(buyers, min);
        s.run();
        boolean[][] out = new boolean[s.rows][s.cols];
        for (int g = 0; g < s.rows; g++) {
            for (int b = 0; b < s.cols; b++) {
                out[g][b] = s.hidden[s.id(g, b)];
            }
        }
        return out;
    }

    private void run() {
        while (true) {
            perSum();
            boolean[] leak = derivableSmallGroup(hiddenIds());
            if (leak == null) {
                return;
            }
            int pick = repair(leak);
            if (pick < 0) {
                return; // nothing left to hide but the grand total
            }
            hidden[pick] = true;
        }
    }

    private void perSum() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int[] sum : sums) {
                double small = 0;
                int fewest = -1;
                for (int i = 1; i < sum.length; i++) {
                    int item = sum[i];
                    if (hidden[item]) {
                        small += buyers(item);
                    } else if (fewest < 0 || buyers(item) < buyers(fewest)) {
                        fewest = item;
                    }
                }
                if (small >= 1 && small < min && fewest >= 0) {
                    hidden[fewest] = true;
                    changed = true;
                }
            }
        }
    }

    /** The shown item with the fewest buyers whose hiding stops {@code leak} being fixed; else the fewest overall. */
    private int repair(boolean[] leak) {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < hidden.length; i++) {
            if (!hidden[i] && i != grand) {
                shown.add(i);
            }
        }
        shown.sort((a, b) -> Double.compare(buyers(a), buyers(b))); // stable: ties keep gender and band order
        for (int candidate : shown) {
            hidden[candidate] = true;
            List<Integer> ids = hiddenIds();
            double[] w = new double[ids.size()];
            for (int j = 0; j < ids.size(); j++) {
                w[j] = leak[ids.get(j)] ? 1 : 0;
            }
            boolean fixed = !inRowSpace(rref(equations(ids)), w);
            hidden[candidate] = false;
            if (fixed) {
                return candidate;
            }
        }
        return shown.isEmpty() ? -1 : shown.get(0);
    }

    /**
     * A group of hidden items (as a mask over item ids) whose buyers the equations fix exactly and that holds 1 to
     * min−1 buyers but not every buyer; null when there is none. Every 0/1 vector in the row space is found: in
     * reduced row echelon form a vector of the space is the sum of the rows at its pivots, so trying each subset of
     * the (at most one per sum) rows covers them all.
     */
    private boolean[] derivableSmallGroup(List<Integer> ids) {
        List<double[]> basis = rref(equations(ids));
        double total = buyers(grand);
        int r = basis.size();
        for (long mask = 1; mask < (1L << r); mask++) {
            double[] v = new double[ids.size()];
            for (int j = 0; j < r; j++) {
                if ((mask & (1L << j)) != 0) {
                    double[] row = basis.get(j);
                    for (int k = 0; k < v.length; k++) {
                        v[k] += row[k];
                    }
                }
            }
            double group = 0;
            boolean zeroOne = true;
            for (int k = 0; k < v.length && zeroOne; k++) {
                if (Math.abs(v[k] - 1) < EPS) {
                    group += buyers(ids.get(k));
                } else if (Math.abs(v[k]) >= EPS) {
                    zeroOne = false;
                }
            }
            if (zeroOne && group >= 1 && group < min && group != total) {
                boolean[] out = new boolean[hidden.length];
                for (int k = 0; k < v.length; k++) {
                    out[ids.get(k)] = Math.abs(v[k] - 1) < EPS;
                }
                return out;
            }
        }
        return null;
    }

    /** One equation per sum over the hidden items: parts − total = shown figures (the right side is not needed). */
    private List<double[]> equations(List<Integer> ids) {
        List<double[]> out = new ArrayList<>();
        for (int[] sum : sums) {
            double[] eq = new double[ids.size()];
            for (int i = 0; i < sum.length; i++) {
                int at = ids.indexOf(sum[i]);
                if (at >= 0) {
                    eq[at] += i == 0 ? -1 : 1;
                }
            }
            out.add(eq);
        }
        return out;
    }

    /** The non-zero rows of the reduced row echelon form, each with a leading 1. */
    private static List<double[]> rref(List<double[]> equations) {
        double[][] m = equations.stream().map(double[]::clone).toArray(double[][]::new);
        int width = m.length == 0 ? 0 : m[0].length;
        int rank = 0;
        for (int c = 0; c < width && rank < m.length; c++) {
            int pivot = -1;
            for (int r = rank; r < m.length; r++) {
                if (Math.abs(m[r][c]) > EPS) {
                    pivot = r;
                    break;
                }
            }
            if (pivot < 0) {
                continue;
            }
            double[] swap = m[pivot];
            m[pivot] = m[rank];
            m[rank] = swap;
            double lead = m[rank][c];
            for (int k = 0; k < width; k++) {
                m[rank][k] /= lead;
            }
            for (int r = 0; r < m.length; r++) {
                if (r != rank && Math.abs(m[r][c]) > EPS) {
                    double f = m[r][c];
                    for (int k = 0; k < width; k++) {
                        m[r][k] -= f * m[rank][k];
                    }
                }
            }
            rank++;
        }
        List<double[]> out = new ArrayList<>();
        for (int r = 0; r < rank; r++) {
            out.add(m[r]);
        }
        return out;
    }

    private static boolean inRowSpace(List<double[]> basis, double[] w) {
        double[] rest = w.clone();
        for (double[] row : basis) {
            int pivot = 0;
            while (Math.abs(row[pivot]) < EPS) {
                pivot++;
            }
            double f = rest[pivot];
            if (Math.abs(f) > EPS) {
                for (int k = 0; k < rest.length; k++) {
                    rest[k] -= f * row[k];
                }
            }
        }
        for (double x : rest) {
            if (Math.abs(x) > EPS) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> hiddenIds() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < hidden.length; i++) {
            if (hidden[i]) {
                out.add(i);
            }
        }
        return out;
    }

    private int id(int g, int b) {
        return g * cols + b;
    }

    private double buyers(int item) {
        return buyers[item / cols][item % cols];
    }
}
