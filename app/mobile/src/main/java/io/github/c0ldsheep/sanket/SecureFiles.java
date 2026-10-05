package io.github.c0ldsheep.sanket;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * App-private files encrypted with AES-256-GCM. The key is generated inside the Android Keystore,
 * cannot be exported, and is deleted together with the data when the user wipes it. Writes go to
 * a temporary file first, so a crash never leaves half a file behind.
 */
final class SecureFiles {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "sanket-local-data";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecureFiles() {}

    static synchronized void write(Context ctx, String name, String text) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Unexpected IV");
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        File target = new File(ctx.getFilesDir(), name);
        File temp = new File(ctx.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(iv);
            out.write(body);
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) throw new IOException("Could not replace " + name);
    }

    /** The decrypted text, or null when the file does not exist. */
    static synchronized String read(Context ctx, String name) throws GeneralSecurityException, IOException {
        File file = new File(ctx.getFilesDir(), name);
        if (!file.exists()) return null;
        byte[] all = Files.readAllBytes(file.toPath());
        if (all.length <= IV_BYTES) throw new IOException(name + " is truncated");
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
        return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    }

    static synchronized void delete(Context ctx, String name) {
        File file = new File(ctx.getFilesDir(), name);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    static synchronized void deleteKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
