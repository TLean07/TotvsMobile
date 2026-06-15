package com.tlean07.totvs.auth.passkey;

import android.content.Context;
import android.os.Build;

import androidx.credentials.CredentialManager;

/**
 * Passkey / Credential Manager scaffold.
 *
 * The Credential Manager API (with Play Services / Samsung Pass as providers on
 * Samsung devices) is the standard, FIDO2-backed way to register and assert
 * passkeys on Android. The full flow needs three additive backend endpoints:
 *
 * <pre>
 *   POST /api/mobile/auth/passkey/challenge  -> server challenge + rp info
 *   POST /api/mobile/devices/register        -> store credentialId + public key
 *   POST /api/mobile/auth/passkey/verify     -> verify assertion, issue tokens
 * </pre>
 *
 * Those endpoints are documented in the project README as the next step. Until
 * they exist on the shared backend, the app uses {@link
 * com.tlean07.totvs.auth.biometric.BiometricHelper} as the local security gate,
 * which protects the same server-issued session. This class isolates the
 * Credential Manager handle so the passkey flow can be wired in without touching
 * the rest of the app.
 */
public final class PasskeyManager {

    private final CredentialManager credentialManager;

    public PasskeyManager(Context context) {
        this.credentialManager = CredentialManager.create(context.getApplicationContext());
    }

    /** Credential Manager passkeys require Android 9+ and a credential provider. */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    public CredentialManager credentialManager() {
        return credentialManager;
    }
}
