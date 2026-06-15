package com.tlean07.totvs.ui.users;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tlean07.totvs.databinding.ItemUserBinding;
import com.tlean07.totvs.network.dto.UserDto;

import java.util.ArrayList;
import java.util.List;

/** Admin list of users with an activate/deactivate action per row. */
public class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {

    public interface OnToggle {
        void onToggle(UserDto user);
    }

    private final List<UserDto> items = new ArrayList<>();
    private final OnToggle onToggle;
    private String currentUserId;

    public UserAdapter(OnToggle onToggle) {
        this.onToggle = onToggle;
    }

    public void submit(List<UserDto> users, String currentUserId) {
        this.currentUserId = currentUserId;
        items.clear();
        if (users != null) {
            items.addAll(users);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemUserBinding b = ItemUserBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        UserDto u = items.get(position);
        ItemUserBinding b = holder.b;

        b.userName.setText(u.name != null ? u.name : "—");
        b.userEmail.setText(u.email != null ? u.email : "");
        b.userInitial.setText(initial(u));
        b.userRole.setText(u.isAdmin() ? "Administrador" : "Usuário");

        if (u.active) {
            b.userStatus.setText("Ativo");
            b.userStatus.setTextColor(Color.parseColor("#047857"));
            b.userStatus.setBackgroundColor(Color.parseColor("#ECFDF5"));
        } else {
            b.userStatus.setText("Inativo");
            b.userStatus.setTextColor(Color.parseColor("#64748B"));
            b.userStatus.setBackgroundColor(Color.parseColor("#F1F5F9"));
        }

        boolean isSelf = u.id != null && u.id.equals(currentUserId);
        b.userToggle.setText(u.active ? (isSelf ? "Você" : "Desativar") : "Ativar");
        b.userToggle.setEnabled(!isSelf);
        b.userToggle.setStrokeColorResource(u.active
                ? com.tlean07.totvs.R.color.totvs_danger
                : com.tlean07.totvs.R.color.totvs_accent);
        b.userToggle.setTextColor(u.active
                ? Color.parseColor("#E11D48")
                : Color.parseColor("#047857"));
        b.userToggle.setOnClickListener(v -> {
            if (!isSelf && onToggle != null) {
                onToggle.onToggle(u);
            }
        });
    }

    private String initial(UserDto u) {
        String base = u.name != null && !u.name.isBlank() ? u.name : (u.email != null ? u.email : "U");
        return base.substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemUserBinding b;

        VH(ItemUserBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
