package com.tlean07.totvs.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.Nullable;

import com.tlean07.totvs.util.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Input devices the user may pick for a recording: the phone's own microphones,
 * a wired or USB headset, a desk/USB microphone, a Bluetooth headset. A choice is
 * stored as a stable key (type + address + product name) because device ids
 * change between sessions; {@link #resolve} maps it back to whatever is plugged
 * in right now, and {@link #route}/{@link #unroute} switch the audio path for
 * Bluetooth devices, which only capture over an active SCO/LE link.
 */
public final class InputDevices {

    /** "auto" = let Android pick (usually the built-in microphone, or the headset when connected). */
    public static final String AUTO = "";

    public static final class Choice {
        public final String key;
        public final String label;
        public final AudioDeviceInfo device;

        Choice(String key, String label, AudioDeviceInfo device) {
            this.key = key;
            this.label = label;
            this.device = device;
        }
    }

    private static final String TAG = "InputDevices";

    private InputDevices() {
    }

    public static List<Choice> list(Context context) {
        List<Choice> out = new ArrayList<>();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return out;
        }
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!d.isSource() || !usable(d.getType())) {
                continue;
            }
            String key = keyOf(d);
            boolean dup = false;
            for (Choice c : out) {
                if (c.key.equals(key)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(new Choice(key, label(d), d));
            }
        }
        return out;
    }

    @Nullable
    public static AudioDeviceInfo resolve(Context context, @Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        List<Choice> now = list(context);
        for (Choice c : now) {
            if (c.key.equals(key)) {
                return c.device;
            }
        }
        // same kind of device (e.g. another USB microphone) is better than silently falling back
        String type = key.split("\\|", 2)[0];
        for (Choice c : now) {
            if (c.key.startsWith(type + "|")) {
                return c.device;
            }
        }
        return null;
    }

    public static String keyOf(AudioDeviceInfo d) {
        return d.getType() + "|" + d.getAddress() + "|" + d.getProductName();
    }

    public static String label(AudioDeviceInfo d) {
        String product = String.valueOf(d.getProductName()).trim();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                String addr = String.valueOf(d.getAddress()).toLowerCase();
                if (addr.contains("back")) {
                    return "Microfone do celular (traseiro)";
                }
                if (addr.contains("top") || addr.contains("front")) {
                    return "Microfone do celular (superior)";
                }
                return "Microfone do celular";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Fone de ouvido com fio";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "Fone USB" + suffix(product);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "Microfone USB" + suffix(product);
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth" + suffix(product);
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "Bluetooth LE" + suffix(product);
            case AudioDeviceInfo.TYPE_DOCK:
                return "Dock" + suffix(product);
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "Entrada de linha" + suffix(product);
            default:
                return product.isEmpty() ? "Entrada " + d.getType() : product;
        }
    }

    private static String suffix(String product) {
        return product.isEmpty() ? "" : " · " + product;
    }

    private static boolean usable(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_TELEPHONY:
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
            case AudioDeviceInfo.TYPE_FM_TUNER:
            case AudioDeviceInfo.TYPE_TV_TUNER:
            case AudioDeviceInfo.TYPE_IP:
            case AudioDeviceInfo.TYPE_BUS:
                return false;
            default:
                return true;
        }
    }

    public static boolean isBluetooth(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    /** Bluetooth microphones only deliver audio while the phone routes communication to them. */
    public static void route(Context context, AudioDeviceInfo d) {
        if (!isBluetooth(d)) {
            return;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!am.setCommunicationDevice(d)) {
                    Logger.w(TAG, "setCommunicationDevice recusado para " + label(d));
                }
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            }
        } catch (RuntimeException e) {
            Logger.w(TAG, "rota Bluetooth falhou: " + e.getMessage());
        }
    }

    public static void unroute(Context context, @Nullable AudioDeviceInfo d) {
        if (d == null || !isBluetooth(d)) {
            return;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
        } catch (RuntimeException e) {
            Logger.w(TAG, "desfazer rota Bluetooth falhou: " + e.getMessage());
        }
    }
}
