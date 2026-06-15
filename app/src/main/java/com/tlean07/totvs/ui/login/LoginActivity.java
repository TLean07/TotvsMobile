package com.tlean07.totvs.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.auth.AuthRepository;
import com.tlean07.totvs.auth.biometric.BiometricHelper;
import com.tlean07.totvs.databinding.ActivityLoginBinding;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.ui.MainActivity;
import com.tlean07.totvs.util.SafeAreaHelper;

/** Email/password login with an optional biometric quick-unlock. */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private AuthRepository authRepository;
    private SessionManager session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        session = TotvsApp.get().session();
        authRepository = TotvsApp.get().authRepository();

        // Login is a sensitive screen: block screenshots and respect safe areas.
        SecurityManager.applySecureFlag(this, true, session);
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.loginHeader);
        SafeAreaHelper.applyBottomInset(binding.loginRoot);

        binding.buttonLogin.setOnClickListener(v -> attemptLogin());
        binding.buttonBiometric.setOnClickListener(v -> attemptBiometric());

        maybeShowBiometricButton();
    }

    private void maybeShowBiometricButton() {
        boolean canQuickUnlock = session.hasPersistentSession()
                && session.isBiometricEnabled()
                && BiometricHelper.isAvailable(this);
        binding.buttonBiometric.setVisibility(canQuickUnlock ? View.VISIBLE : View.GONE);
    }

    private void attemptBiometric() {
        BiometricHelper.prompt(this,
                getString(R.string.bio_prompt_title),
                getString(R.string.bio_prompt_subtitle),
                getString(R.string.bio_negative),
                new BiometricHelper.PromptCallback() {
                    @Override
                    public void onSuccess() {
                        setLoading(true);
                        authRepository.silentRefresh(result -> {
                            setLoading(false);
                            if (result.success) {
                                goMain();
                            } else {
                                showError(getString(R.string.error_session_expired));
                                maybeShowBiometricButton();
                            }
                        });
                    }
                });
    }

    private void attemptLogin() {
        hideError();
        String email = binding.inputEmail.getText().toString().trim();
        String password = binding.inputPassword.getText().toString();

        if (TextUtils.isEmpty(email) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.login_error_invalid_email));
            return;
        }
        if (password.length() < 8) {
            showError(getString(R.string.login_error_short_password));
            return;
        }

        setLoading(true);
        authRepository.login(email, password, result -> {
            setLoading(false);
            if (result.success) {
                offerBiometricThenContinue();
            } else {
                showError(result.errorMessage);
            }
        });
    }

    /** After the first successful login, offer to enable biometric quick-unlock. */
    private void offerBiometricThenContinue() {
        if (!session.isBiometricEnabled() && BiometricHelper.isAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bio_enable_title)
                    .setMessage(R.string.bio_enable_message)
                    .setPositiveButton(R.string.bio_enable_yes, (d, w) -> {
                        session.setBiometricEnabled(true);
                        goMain();
                    })
                    .setNegativeButton(R.string.bio_enable_no, (d, w) -> goMain())
                    .setCancelable(false)
                    .show();
        } else {
            goMain();
        }
    }

    private void setLoading(boolean loading) {
        binding.buttonLogin.setEnabled(!loading);
        binding.buttonBiometric.setEnabled(!loading);
        binding.buttonLogin.setText(loading ? R.string.login_loading : R.string.login_button);
    }

    private void showError(String message) {
        binding.loginError.setText(message);
        binding.loginError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        binding.loginError.setVisibility(View.GONE);
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
