package com.tlean07.totvs.ui.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.FragmentDashboardBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.ui.components.AlertaAdapter;
import com.tlean07.totvs.ui.components.StatAdapter;
import com.tlean07.totvs.ui.components.StatItem;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.ArrayList;
import java.util.List;

public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;
    private final StatAdapter statAdapter = new StatAdapter();
    private final AlertaAdapter alertaAdapter = new AlertaAdapter();
    private DataRepository data;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SafeAreaHelper.applyTopInset(binding.dashHeader);
        data = TotvsApp.get().dataRepository();

        binding.statsRecycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.statsRecycler.setAdapter(statAdapter);
        binding.alertsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.alertsRecycler.setAdapter(alertaAdapter);

        AuthUser user = TotvsApp.get().session().getCurrentUser();
        if (user != null && user.name != null) {
            binding.dashGreeting.setText("Olá, " + firstName(user.name));
        }

        binding.swipeRefresh.setOnRefreshListener(this::load);
        binding.dashRetry.setOnClickListener(v -> load());
        load();
    }

    private void load() {
        binding.swipeRefresh.setRefreshing(true);
        hideState();
        data.dashboard(result -> {
            binding.swipeRefresh.setRefreshing(false);
            if (result.success && result.data != null) {
                render(result.data);
            } else {
                showError(result.errorMessage);
            }
        });
    }

    private void render(DashboardResponse d) {
        List<StatItem> stats = new ArrayList<>();
        stats.add(new StatItem("Transcrições", String.valueOf(d.totalTranscricoes), R.color.totvs_secondary));
        stats.add(new StatItem("Processadas", String.valueOf(d.transcricoesProcessadas), R.color.totvs_accent));
        stats.add(new StatItem("Oportunidades", String.valueOf(d.totalOportunidades), R.color.totvs_accent));
        stats.add(new StatItem("Riscos", String.valueOf(d.totalRiscos), R.color.totvs_danger));
        stats.add(new StatItem("Alertas novos", String.valueOf(d.alertasNovos), R.color.totvs_warning));
        stats.add(new StatItem("Churn potencial", String.valueOf(d.churnsPotenciais), R.color.totvs_danger));
        statAdapter.submit(stats);

        boolean hasAlerts = d.alertasRecentes != null && !d.alertasRecentes.isEmpty();
        binding.alertsTitle.setVisibility(hasAlerts ? View.VISIBLE : View.GONE);
        alertaAdapter.submit(d.alertasRecentes);
    }

    private void showError(String message) {
        binding.dashState.setText(message != null ? message : getString(R.string.error_generic));
        binding.dashState.setVisibility(View.VISIBLE);
        binding.dashRetry.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        binding.dashState.setVisibility(View.GONE);
        binding.dashRetry.setVisibility(View.GONE);
    }

    private String firstName(String name) {
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
