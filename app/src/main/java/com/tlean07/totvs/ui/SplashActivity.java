package com.tlean07.totvs.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.auth.AuthRepository;
import com.tlean07.totvs.auth.biometric.BiometricHelper;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.ui.login.LoginActivity;

/**
 * Entry point. Decides where to send the user:
 * <ol>
 *   <li>No saved session → Login.</li>
 *   <li>Saved session + biometric lock due → biometric gate, then silent refresh.</li>
 *   <li>Saved session, no lock → silent refresh straight to the dashboard.</li>
 * </ol>
 * The biometric gate only unlocks a local session restore; the backend still
 * validates the refresh (a revoked device/session fails regardless).
 */
public class SplashActivity extends AppCompatActivity {

    private SessionManager session;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        session = TotvsApp.get().session();
        authRepository = TotvsApp.get().authRepository();

        decide();
    }

    private void decide() {
        if (!session.hasPersistentSession()) {
            goLogin();
            return;
        }
        if (SecurityManager.shouldLock(session) && BiometricHelper.isAvailable(this)) {
            promptBiometric();
        } else {
            restoreSession();
        }
    }

    private void promptBiometric() {
        BiometricHelper.prompt(this,
                getString(R.string.bio_prompt_title),
                getString(R.string.bio_prompt_subtitle),
                getString(R.string.bio_negative),
                new BiometricHelper.PromptCallback() {
                    @Override
                    public void onSuccess() {
                        restoreSession();
                    }

                    @Override
                    public void onCancel() {
                        goLogin();
                    }

                    @Override
                    public void onError(String message) {
                        goLogin();
                    }
                });
    }

    private void restoreSession() {
        authRepository.silentRefresh(result -> {
            if (result.success) {
                goMain();
            } else {
                goLogin();
            }
        });
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void goLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
