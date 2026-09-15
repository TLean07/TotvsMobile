package com.tlean07.totvs.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityMainBinding;
import com.tlean07.totvs.network.AuthEvents;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.ui.ai.AiFragment;
import com.tlean07.totvs.ui.dashboard.DashboardFragment;
import com.tlean07.totvs.ui.login.LoginActivity;
import com.tlean07.totvs.ui.profile.ProfileFragment;
import com.tlean07.totvs.ui.security.SecurityFragment;
import com.tlean07.totvs.util.SafeAreaHelper;

/** Shell hosting the four main sections behind a bottom navigation bar. */
public class MainActivity extends AppCompatActivity implements AuthEvents.Listener {

    private ActivityMainBinding binding;
    private SessionManager session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        session = TotvsApp.get().session();
        SecurityManager.applySecureFlag(this, true, session);

        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyBottomInset(binding.bottomNav);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                show(new DashboardFragment(), "dashboard");
            } else if (id == R.id.nav_ai) {
                show(new AiFragment(), "ai");
            } else if (id == R.id.nav_profile) {
                show(new ProfileFragment(), "profile");
            } else if (id == R.id.nav_security) {
                show(new SecurityFragment(), "security");
            }
            return true;
        });

        if (savedInstanceState == null) {
            binding.bottomNav.setSelectedItemId(R.id.nav_dashboard);
        }
        com.tlean07.totvs.work.AlertasWorker.schedule(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 71);
        }
    }

    private void show(Fragment fragment, String tag) {
        FragmentManager fm = getSupportFragmentManager();
        fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragmentContainer, fragment, tag)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AuthEvents.setListener(this);

        if (!session.hasPersistentSession()) {
            onSessionExpired();
            return;
        }
        // Re-lock after inactivity: bounce through the splash, which re-auths.
        if (!session.isLoggedInMemory() || SecurityManager.shouldLock(session)) {
            session.lock();
            startActivity(new Intent(this, SplashActivity.class));
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        session.touch();
        AuthEvents.setListener(null);
    }

    @Override
    public void onSessionExpired() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
