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

    /**
     * Pads only by the on-screen keyboard (the part of it that is not already
     * covered by the navigation-bar padding), and scrolls the focused field back
     * into view once the keyboard is up. For scroll containers that sit above a
     * bar which already handles the navigation inset.
     */
    public static void applyImeInset(View view) {
        final int base = view.getPaddingBottom();
        final int l = view.getPaddingLeft();
        final int t = view.getPaddingTop();
        final int r = view.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            int ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int extra = Math.max(0, ime - nav);
            if (v.getPaddingBottom() != base + extra) {
                v.setPadding(l, t, r, base + extra);
                if (extra > 0) {
                    revealFocused(v);
                }
            }
            return windowInsets;
        });
        requestInsets(view);
    }

    /**
     * Makes a bottom sheet usable with the keyboard open: the window resizes,
     * the sheet expands and its content gets the keyboard height as bottom
     * padding so every field and button can still be scrolled into view.
     */
    public static void keyboardAwareSheet(com.google.android.material.bottomsheet.BottomSheetDialog dialog, View content) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        final int base = content.getPaddingBottom();
        final int l = content.getPaddingLeft();
        final int t = content.getPaddingTop();
        final int r = content.getPaddingRight();
        // Edge-to-edge sheet themes deliver the keyboard as an inset: pad the content by it.
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            int ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int extra = Math.max(0, ime - nav);
            if (v.getPaddingBottom() != base + extra) {
                v.setPadding(l, t, r, base + extra);
                if (extra > 0) {
                    dialog.getBehavior().setState(
                            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                    revealFocused(v);
                }
            }
            return windowInsets;
        });
        requestInsets(content);
        // Non-edge-to-edge sheet themes (the MaterialComponents default) let the dialog's
        // CoordinatorLayout consume the insets and pad itself by the keyboard, so the
        // sheet view simply gets shorter: react to that instead.
        content.post(() -> {
            View sheetView = content.getParent() instanceof View ? (View) content.getParent() : content;
            sheetView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int height = bottom - top;
                int oldHeight = oldBottom - oldTop;
                if (oldHeight > 0 && height < oldHeight) {
                    dialog.getBehavior().setState(
                            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                    revealFocused(content);
                }
            });
        });
    }

    /**
     * Scrolls the focused field above the keyboard. Done by hand because a scroll
     * container that is taller than the visible area (bottom sheets) believes the
     * field is already on screen and ignores requestRectangleOnScreen.
     */
    private static void revealFocused(View root) {
        Runnable reveal = () -> {
            View focused = root.findFocus();
            if (focused == null) {
                return;
            }
            // Edge-to-edge windows keep their full height and report the keyboard as an inset;
            // fitting windows are simply shorter. Both cases give the usable bottom edge here.
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
            int ime = insets == null ? 0 : insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int[] loc = new int[2];
            focused.getLocationInWindow(loc);
            int margin = (int) (24 * root.getResources().getDisplayMetrics().density);
            int fieldBottom = loc[1] + focused.getHeight() + margin;
            int limit = root.getRootView().getHeight() - ime;
            if (fieldBottom <= limit) {
                return;
            }
            android.view.ViewParent p = focused.getParent();
            while (p != null && !(p instanceof androidx.core.widget.NestedScrollView)
                    && !(p instanceof android.widget.ScrollView)) {
                p = p.getParent();
            }
            if (p instanceof androidx.core.widget.NestedScrollView) {
                ((androidx.core.widget.NestedScrollView) p).smoothScrollBy(0, fieldBottom - limit);
            } else if (p instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) p).smoothScrollBy(0, fieldBottom - limit);
            }
        };
        // After the layout with the new padding, and again once the keyboard animation settled.
        root.post(reveal);
        root.postDelayed(reveal, 350);
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
