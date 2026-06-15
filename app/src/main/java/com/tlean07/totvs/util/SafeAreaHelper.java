package com.tlean07.totvs.util;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge safe-area handling for every device — including tall/curved
 * panels and cutouts like the Samsung Galaxy S25 Ultra, and both gesture and
 * 3-button navigation.
 *
 * The app draws behind the system bars (transparent status/navigation bars set
 * in the theme); these helpers re-apply the correct dynamic padding from
 * {@link WindowInsetsCompat} so no content ever sits under the status bar,
 * navigation/gesture bar or a display cutout. Never use hardcoded bar padding.
 */
public final class SafeAreaHelper {

    private SafeAreaHelper() {
    }

    /** Call once per Activity to enable edge-to-edge drawing. */
    public static void enableEdgeToEdge(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    }

    /** Pads a root view on all four sides by the union of system bars + display cutout. */
    public static void applySystemBarsPadding(View view) {
        final int l = view.getPaddingLeft();
        final int t = view.getPaddingTop();
        final int r = view.getPaddingRight();
        final int b = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return windowInsets;
        });
        requestInsets(view);
    }

    /** Pads only the top (status bar + cutout) — e.g. for an app bar / header. */
    public static void applyTopInset(View view) {
        final int base = view.getPaddingTop();
        final int l = view.getPaddingLeft();
        final int r = view.getPaddingRight();
        final int b = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(l + bars.left, base + bars.top, r + bars.right, b);
            return windowInsets;
        });
        requestInsets(view);
    }

    /** Pads only the bottom (navigation/gesture bar + IME) — e.g. for a bottom nav. */
    public static void applyBottomInset(View view) {
        final int base = view.getPaddingBottom();
        final int l = view.getPaddingLeft();
        final int t = view.getPaddingTop();
        final int r = view.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(l, t, r, base + bars.bottom);
            return windowInsets;
        });
        requestInsets(view);
    }

    private static void requestInsets(View view) {
        if (ViewCompat.isAttachedToWindow(view)) {
            ViewCompat.requestApplyInsets(view);
        } else {
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    ViewCompat.requestApplyInsets(v);
                    v.removeOnAttachStateChangeListener(this);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }
    }
}
