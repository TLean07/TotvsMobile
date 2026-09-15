package com.tlean07.totvs.audio.dsp;

/**
 * Sufficient statistics of a set of feature vectors (count, sum, sum of outer
 * products) for a full-covariance Gaussian, plus the Bayesian Information
 * Criterion (BIC) tests used by {@link OfflineDiarizer}: "do these two sets of
 * frames sound like one voice or two?". Everything is relative to the data
 * itself, so a reverberant meeting room and a clean headset get the same
 * treatment without hand-tuned distance thresholds.
 */
final class GaussianStats {

    final int dims;
    int n;
    final double[] sum;
    /** Row-major dims × dims sum of x·xᵀ. */
    final double[] sq;
    /** Pitch statistics over voiced frames (log2 Hz). */
    int voiced;
    double pitchSum;
    double pitchSq;

    GaussianStats(int dims) {
        this.dims = dims;
        this.sum = new double[dims];
        this.sq = new double[dims * dims];
    }

    void add(float[] x, int offset, float pitchOctaves) {
        n++;
        for (int i = 0; i < dims; i++) {
            double xi = x[offset + i];
            sum[i] += xi;
            int row = i * dims;
            for (int j = 0; j <= i; j++) {
                sq[row + j] += xi * x[offset + j];
            }
        }
        if (!Float.isNaN(pitchOctaves)) {
            voiced++;
            pitchSum += pitchOctaves;
            pitchSq += pitchOctaves * (double) pitchOctaves;
        }
    }

    void addAll(GaussianStats o) {
        n += o.n;
        for (int i = 0; i < dims; i++) {
            sum[i] += o.sum[i];
        }
        for (int i = 0; i < sq.length; i++) {
            sq[i] += o.sq[i];
        }
        voiced += o.voiced;
        pitchSum += o.pitchSum;
        pitchSq += o.pitchSq;
    }

    void subtract(GaussianStats o) {
        n -= o.n;
        for (int i = 0; i < dims; i++) {
            sum[i] -= o.sum[i];
        }
        for (int i = 0; i < sq.length; i++) {
            sq[i] -= o.sq[i];
        }
        voiced -= o.voiced;
        pitchSum -= o.pitchSum;
        pitchSq -= o.pitchSq;
    }

    static GaussianStats merged(GaussianStats a, GaussianStats b) {
        GaussianStats m = new GaussianStats(a.dims);
        m.addAll(a);
        m.addAll(b);
        return m;
    }

    GaussianStats copy() {
        GaussianStats m = new GaussianStats(dims);
        m.addAll(this);
        return m;
    }

    double mean(int i) {
        return n == 0 ? 0 : sum[i] / n;
    }

    double meanPitch() {
        return voiced == 0 ? Double.NaN : pitchSum / voiced;
    }

    double pitchStd() {
        if (voiced < 2) {
            return Double.NaN;
        }
        double m = pitchSum / voiced;
        return Math.sqrt(Math.max(0, pitchSq / voiced - m * m));
    }

    /**
     * Covariance shrunk towards {@code prior} with weight {@code kappa}
     * pseudo-frames, so a one-second segment still yields a usable matrix.
     * Returns the lower Cholesky factor (row-major) or null if not positive definite.
     */
    double[] cholesky(double[] prior, double kappa) {
        double[] cov = covariance(prior, kappa);
        double[] l = new double[dims * dims];
        for (int i = 0; i < dims; i++) {
            for (int j = 0; j <= i; j++) {
                double s = cov[i * dims + j];
                for (int k = 0; k < j; k++) {
                    s -= l[i * dims + k] * l[j * dims + k];
                }
                if (i == j) {
                    if (s <= 1e-12) {
                        return null;
                    }
                    l[i * dims + i] = Math.sqrt(s);
                } else {
                    l[i * dims + j] = s / l[j * dims + j];
                }
            }
        }
        return l;
    }

    double[] covariance(double[] prior, double kappa) {
        double[] cov = new double[dims * dims];
        double total = n + kappa;
        for (int i = 0; i < dims; i++) {
            double mi = mean(i);
            for (int j = 0; j <= i; j++) {
                double c = sq[i * dims + j] - n * mi * mean(j);
                c = (c + kappa * prior[i * dims + j]) / total;
                cov[i * dims + j] = c;
                cov[j * dims + i] = c;
            }
        }
        return cov;
    }

    /** log|Σ| of the shrunk covariance. */
    double logDet(double[] prior, double kappa) {
        double[] l = cholesky(prior, kappa);
        if (l == null) {
            return Double.POSITIVE_INFINITY;
        }
        double acc = 0;
        for (int i = 0; i < dims; i++) {
            acc += Math.log(l[i * dims + i]);
        }
        return 2 * acc;
    }

    /** Number of free parameters of a full-covariance Gaussian. */
    static int parameters(int dims) {
        return dims + dims * (dims + 1) / 2;
    }

    /**
     * Log-likelihood gain of modelling {@code a} and {@code b} with two Gaussians
     * instead of one (the generalised likelihood ratio, half of it). Under one
     * voice it hovers around {@code parameters/2}; under two voices it grows
     * with the effective sample size {@link #effectiveN}.
     */
    static double gain(GaussianStats a, GaussianStats b, double[] prior, double kappa) {
        GaussianStats ab = merged(a, b);
        double lab = ab.logDet(prior, kappa);
        double la = a.logDet(prior, kappa);
        double lb = b.logDet(prior, kappa);
        if (Double.isInfinite(lab) || Double.isInfinite(la) || Double.isInfinite(lb)) {
            return 0;
        }
        return 0.5 * (ab.n * lab - a.n * la - b.n * lb);
    }

    /** n₁n₂/(n₁+n₂): the sample size a two-sample statistic actually scales with. */
    static double effectiveN(GaussianStats a, GaussianStats b) {
        return a.n * (double) b.n / Math.max(1, a.n + b.n);
    }
}
