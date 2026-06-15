package com.tlean07.totvs.ui.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.cas.DeviceInfo;
import com.tlean07.totvs.databinding.FragmentProfileBinding;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.ui.login.LoginActivity;
import com.tlean07.totvs.ui.users.UsersActivity;
import com.tlean07.totvs.util.SafeAreaHelper;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SafeAreaHelper.applyTopInset(binding.profileHeader);

        AuthUser user = TotvsApp.get().session().getCurrentUser();
        if (user != null) {
            binding.profileName.setText(user.name != null ? user.name : "—");
            binding.profileEmail.setText(user.email != null ? user.email : "");
            binding.profileRole.setText(user.isAdmin() ? "Administrador" : "Usuário");
            binding.profileInitials.setText(initials(user.name));
        }

        DeviceInfo device = TotvsApp.get().devices().getDevice();
        binding.profileDeviceName.setText(device.deviceName != null ? device.deviceName : "Este dispositivo");
        binding.profileDeviceMeta.setText(device.osVersion + " • app " + device.appVersion);

        // Área de administração só aparece para ADMIN (igual à versão web).
        boolean isAdmin = user != null && user.isAdmin();
        binding.buttonManageUsers.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        binding.buttonManageUsers.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), UsersActivity.class)));

        binding.buttonLogout.setOnClickListener(v -> confirmLogout());
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.logout)
                .setMessage("Deseja encerrar a sessão neste dispositivo?")
                .setPositiveButton(R.string.logout, (d, w) -> doLogout())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void doLogout() {
        TotvsApp.get().authRepository().logout(result -> goLogin());
    }

    private void goLogin() {
        if (!isAdded()) {
            return;
        }
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    private String initials(String name) {
        if (name == null || name.isBlank()) {
            return "–";
        }
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
