package com.tlean07.totvs.ui.users;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityUsersBinding;
import com.tlean07.totvs.databinding.SheetCreateUserBinding;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.dto.CreateUserRequest;
import com.tlean07.totvs.network.dto.UserDto;
import com.tlean07.totvs.security.SecurityManager;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.util.SafeAreaHelper;

import java.util.List;

/** Admin-only screen mirroring the web "Usuários" page (list + create + activate/deactivate). */
public class UsersActivity extends AppCompatActivity {

    private ActivityUsersBinding binding;
    private DataRepository data;
    private SessionManager session;
    private UserAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUsersBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        data = TotvsApp.get().dataRepository();
        session = TotvsApp.get().session();

        SecurityManager.applySecureFlag(this, true, session);
        SafeAreaHelper.enableEdgeToEdge(this);
        SafeAreaHelper.applyTopInset(binding.usersHeader);
        SafeAreaHelper.applyBottomInset(binding.usersRoot);

        adapter = new UserAdapter(this::confirmToggle);
        binding.usersRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.usersRecycler.setAdapter(adapter);

        binding.usersBack.setOnClickListener(v -> finish());
        binding.usersSwipe.setOnRefreshListener(() -> load(true));
        binding.usersFab.setOnClickListener(v -> showCreateDialog());

        load();
    }

    private void load() {
        load(false);
    }

    private void load(boolean forceRefresh) {
        binding.usersSwipe.setRefreshing(true);
        binding.usersState.setVisibility(View.GONE);
        data.users(forceRefresh, result -> {
            binding.usersSwipe.setRefreshing(false);
            if (result.success) {
                render(result.data);
            } else {
                adapter.submit(null, null);
                showState(result.errorMessage);
            }
        });
    }

    private void render(List<UserDto> users) {
        String currentId = session.getCurrentUser() != null ? session.getCurrentUser().id : null;
        adapter.submit(users, currentId);

        int total = users != null ? users.size() : 0;
        int ativos = 0;
        int admins = 0;
        if (users != null) {
            for (UserDto u : users) {
                if (u.active) ativos++;
                if (u.isAdmin()) admins++;
            }
        }
        binding.usersStatTotal.setText(total + "\nTotal");
        binding.usersStatAtivos.setText(ativos + "\nAtivos");
        binding.usersStatAdmins.setText(admins + "\nAdmins");

        if (total == 0) {
            showState("Nenhum usuário encontrado.");
        } else {
            binding.usersState.setVisibility(View.GONE);
        }
    }

    private void showState(String message) {
        binding.usersStateText.setText(message != null ? message : "Não foi possível carregar.");
        binding.usersState.setVisibility(View.VISIBLE);
    }

    private void showCreateDialog() {
        SheetCreateUserBinding sb = SheetCreateUserBinding.inflate(getLayoutInflater());
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.Theme_Totvs_BottomSheet);
        sheet.setContentView(sb.getRoot());
        SafeAreaHelper.keyboardAwareSheet(sheet, sb.getRoot());

        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new String[]{"Usuário", "Administrador"});
        sb.inputNewRole.setAdapter(roleAdapter);
        sb.inputNewRole.setText("Usuário", false);

        sb.buttonCriarUsuario.setOnClickListener(v -> {
            String name = text(sb.inputNewName.getText());
            String email = text(sb.inputNewEmail.getText());
            String password = sb.inputNewPassword.getText() == null
                    ? "" : sb.inputNewPassword.getText().toString();
            String role = "Administrador".contentEquals(sb.inputNewRole.getText()) ? "ADMIN" : "USER";

            if (TextUtils.isEmpty(name)) {
                sb.inputNewName.setError("Informe o nome");
                return;
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                sb.inputNewEmail.setError("E-mail inválido");
                return;
            }
            if (password.length() < 12) {
                sb.inputNewPassword.setError("Mínimo de 12 caracteres");
                return;
            }
            sheet.dismiss();
            create(new CreateUserRequest(name, email, password, role));
        });

        sheet.show();
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private void create(CreateUserRequest request) {
        binding.usersProgress.setVisibility(View.VISIBLE);
        data.criarUsuario(request, result -> {
            binding.usersProgress.setVisibility(View.GONE);
            if (result.success) {
                Toast.makeText(this, "Usuário criado", Toast.LENGTH_SHORT).show();
                load();
            } else {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmToggle(UserDto user) {
        boolean next = !user.active;
        new AlertDialog.Builder(this)
                .setTitle(next ? "Ativar usuário" : "Desativar usuário")
                .setMessage((next ? "Ativar " : "Desativar ") + user.name + "?")
                .setPositiveButton("Confirmar", (d, w) -> toggle(user, next))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void toggle(UserDto user, boolean active) {
        binding.usersProgress.setVisibility(View.VISIBLE);
        data.setUsuarioAtivo(user.id, active, result -> {
            binding.usersProgress.setVisibility(View.GONE);
            if (result.success) {
                load();
            } else {
                Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }
}
