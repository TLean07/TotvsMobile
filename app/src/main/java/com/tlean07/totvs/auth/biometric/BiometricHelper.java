package com.tlean07.totvs.auth.biometric;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.tlean07.totvs.util.Logger;

/**
 * Java port of the LifeCode {@code BiometricHelper.kt} reference, adapted for
 * TOTVS Mobile.
 *
 * Key difference from the reference: this app does <b>not</b> store the user's
 * email/password. Biometrics only act as a local gate that unlocks an existing
 * server-issued session (the refresh-token cookie in encrypted storage). The
 * backend still validates every request, so biometric success never bypasses
 * server-side auth or a revoked device.
 */
public final class BiometricHelper {

    public static final int ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private static final int BIOMETRIC_ONLY =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK;

    public interface PromptCallback {
        void onSuccess();

        /** Hard error (lockout, no hardware, etc.). */
        default void onError(String message) {
        }

        /** User cancelled / pressed the negative button. */
        default void onCancel() {
        }
    }

    private BiometricHelper() {
    }

    /** True when the device can authenticate the user with biometrics or device credential. */
    public static boolean isAvailable(Context context) {
        try {
            int status = BiometricManager.from(context).canAuthenticate(BIOMETRIC_ONLY);
            return status == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            // Some OEMs (notably certain Samsung builds) throw here instead of
            // returning a status code. Treat any failure as "not available".
            Logger.e("BiometricHelper", "canAuthenticate failed", e);
            return false;
        }
    }

    /** True when a device PIN/pattern/password fallback is usable. */
    public static boolean canUseDeviceCredential(Context context) {
        try {
            int status = BiometricManager.from(context)
                    .canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            return status == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public static void prompt(@NonNull FragmentActivity activity,
                              @NonNull String title,
                              @NonNull String subtitle,
                              @NonNull String negativeButton,
                              @NonNull PromptCallback callback) {

        if (activity.isFinishing() || activity.isDestroyed()) {
            callback.onCancel();
            return;
        }

        BiometricPrompt biometricPrompt = new BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_CANCELED) {
                            callback.onCancel();
                        } else {
                            callback.onError(errString.toString());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        // A single non-match — the prompt stays open; nothing to do.
                    }
                });

        try {
            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText(negativeButton)
                    .setAllowedAuthenticators(BIOMETRIC_ONLY)
                    .setConfirmationRequired(false)
                    .build();

            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            // Never let a biometric initialisation problem crash the app — fall
            // back to password login instead.
            Logger.e("BiometricHelper", "authenticate failed", e);
            callback.onError("Não foi possível iniciar a biometria. Use sua senha.");
        }
    }
}
