package com.tlean07.totvs.audio.store;

import android.content.Context;

import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

/**
 * Encrypts finished recordings at rest with a key held in the Android Keystore
 * (the same MasterKey scheme the app uses for its tokens). Playback and upload
 * work on a short-lived plaintext copy in the cache directory.
 */
public final class RecordingCrypto {

    public static final String SUFFIX = ".enc";

    private final Context app;
    private MasterKey key;

    public RecordingCrypto(Context context) {
        this.app = context.getApplicationContext();
    }

    private MasterKey key() throws GeneralSecurityException, IOException {
        if (key == null) {
            key = new MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        }
        return key;
    }

    /** Encrypts {@code plain} into {@code plain.name + .enc} next to it and deletes the plaintext. */
    public File encrypt(File plain) throws IOException {
        File target = new File(plain.getParentFile(), plain.getName() + SUFFIX);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        try {
            EncryptedFile ef = new EncryptedFile.Builder(app, target, key(),
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
            try (InputStream in = new FileInputStream(plain); OutputStream out = ef.openFileOutput()) {
                copy(in, out);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore indisponível", e);
        }
        //noinspection ResultOfMethodCallIgnored
        plain.delete();
        return target;
    }

    /** Decrypts into {@code into} (overwritten). */
    public File decrypt(File encrypted, File into) throws IOException {
        File parent = into.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cache indisponível");
        }
        try {
            EncryptedFile ef = new EncryptedFile.Builder(app, encrypted, key(),
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build();
            try (InputStream in = ef.openFileInput(); OutputStream out = new FileOutputStream(into)) {
                copy(in, out);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("keystore indisponível", e);
        }
        return into;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }
}
