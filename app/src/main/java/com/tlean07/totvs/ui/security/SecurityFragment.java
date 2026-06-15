package com.tlean07.totvs.ui.security;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.auth.biometric.BiometricHelper;
import com.tlean07.totvs.cas.DeviceInfo;
import com.tlean07.totvs.cas.DeviceManager;
import com.tlean07.totvs.databinding.FragmentSecurityBinding;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.ui.login.LoginActivity;
import com.tlean07.totvs.util.SafeAreaHelper;

public class SecurityFragment extends Fragment {

    private FragmentSecurityBinding binding;
    private SessionManager session;
    private DeviceManager deviceManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSecurityBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SafeAreaHelper.applyTopInset(binding.securityHeader);
        session = TotvsApp.get().session();
        deviceManager = TotvsApp.get().devices();

        binding.switchBiometric.setChecked(session.isBiometricEnabled());
        binding.switchSecureScreen.setChecked(session.isSecureScreenEnabled());

        binding.switchBiometric.setOnCheckedChangeListener((b, checked) -> onBiometricToggle(checked));
        binding.switchSecureScreen.setOnCheckedChangeListener((b, checked) -> {
            session.setSecureScreenEnabled(checked);
            SecurityManager.applySecureFlag(requireActivity(), true, session);
        });

        renderDevice();
        binding.buttonRevoke.setOnClickListener(v -> confirmRevoke());
    }

    private void onBiometricToggle(boolean checked) {
        if (checked && !BiometricHelper.isAvailable(requireContext())) {
            Toast.makeText(requireContext(),
                    "Nenhuma biometria cadastrada neste dispositivo.", Toast.LENGTH_LONG).show();
            binding.switchBiometric.setChecked(false);
            return;
        }
        if (checked) {
            // Confirm enrolment by passing the prompt once.
            BiometricHelper.prompt(requireActivity(),
                    getString(R.string.bio_prompt_title),
                    getString(R.string.bio_prompt_subtitle),
                    getString(R.string.bio_negative),
                    new BiometricHelper.PromptCallback() {
                        @Override
                        public void onSuccess() {
                            session.setBiometricEnabled(true);
                        }

                        @Override
                        public void onCancel() {
                            binding.switchBiometric.setChecked(false);
                        }

                        @Override
                        public void onError(String message) {
                            binding.switchBiometric.setChecked(false);
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            session.setBiometricEnabled(false);
        }
    }

    private void renderDevice() {
        DeviceInfo d = deviceManager.getDevice();
        binding.securityDeviceName.setText(d.deviceName);
        binding.securityDeviceId.setText("ID: " + d.devicePublicId);
        binding.securityDeviceStatus.setText("Status: " + d.status + " • app " + d.appVersion);
    }

    private void confirmRevoke() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sec_revoke)
                .setMessage("Isto encerra a sessão e desvincula este dispositivo. "
                        + "Você precisará entrar novamente.")
                .setPositiveButton(R.string.sec_revoke, (dialog, w) -> doRevoke())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void doRevoke() {
        deviceManager.revokeLocal();
        // Server-side: logout revokes the refresh-token session in MongoDB.
        TotvsApp.get().authRepository().logout(result -> {
            Intent intent = new Intent(requireContext(), LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
