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

import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.databinding.ActivityUsersBinding;
import com.tlean07.totvs.databinding.DialogCreateUserBinding;
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
        binding.usersSwipe.setOnRefreshListener(this::load);
        binding.usersFab.setOnClickListener(v -> showCreateDialog());

        load();
    }

    private void load() {
        binding.usersSwipe.setRefreshing(true);
        binding.usersState.setVisibility(View.GONE);
        data.users(result -> {
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
        DialogCreateUserBinding db = DialogCreateUserBinding.inflate(getLayoutInflater());
        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Usuário", "Administrador"});
        db.newUserRole.setAdapter(roleAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Novo usuário")
                .setView(db.getRoot())
                .setPositiveButton("Criar", null)
                .setNegativeButton("Cancelar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = db.newUserName.getText().toString().trim();
            String email = db.newUserEmail.getText().toString().trim();
            String password = db.newUserPassword.getText().toString();
            String role = db.newUserRole.getSelectedItemPosition() == 1 ? "ADMIN" : "USER";

            if (TextUtils.isEmpty(name)) {
                db.newUserName.setError("Informe o nome");
                return;
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                db.newUserEmail.setError("E-mail inválido");
                return;
            }
            if (password.length() < 12) {
                db.newUserPassword.setError("Mínimo de 12 caracteres");
                return;
            }
            dialog.dismiss();
            create(new CreateUserRequest(name, email, password, role));
        }));
        dialog.show();
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
