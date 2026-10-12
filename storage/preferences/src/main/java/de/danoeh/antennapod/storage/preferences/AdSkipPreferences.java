package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;

public final class AdSkipPreferences {
    public static final String ENABLED = "adSkipEnabled";
    public static final String AUTO_SKIP = "adSkipAutomatic";
    public static final String SHOW_QUEUE_STATUS = "adSkipShowQueueStatus";
    public static final String MOBILE_DATA = "adSkipMobileData";
    public static final String ORIGIN = "adSkipOrigin";
    private static final String GENERATION = "adSkipGeneration";
    private static final String KEY_ALIAS = "AudioAdDetectToken";

    private AdSkipPreferences() {
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    public static boolean autoSkip(Context context) {
        return isEnabled(context) && preferences(context).getBoolean(AUTO_SKIP, false);
    }

    public static boolean showQueueStatus(Context context) {
        return preferences(context).getBoolean(SHOW_QUEUE_STATUS, true);
    }

    public static boolean mobileData(Context context) {
        return preferences(context).getBoolean(MOBILE_DATA, false);
    }

    public static String origin(Context context) {
        return preferences(context).getString(ORIGIN, "https://audioaddetect-pc.tail09f731.ts.net");
    }

    public static String generation(Context context) {
        return preferences(context).getString(GENERATION, "initial");
    }

    public static String validateOrigin(String value) {
        URI uri = URI.create(value.trim());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Invalid HTTPS origin");
        }
        return "https://" + uri.getRawAuthority();
    }

    public static void changeGeneration(Context context) {
        preferences(context).edit().putString(GENERATION, UUID.randomUUID().toString()).commit();
    }

    private static AtomicFile tokenFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "audio-ad-detect-token"));
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    public static synchronized void setToken(Context context, String token)
            throws GeneralSecurityException, IOException {
        if (token.trim().isEmpty() || token.contains("\n") || token.contains("\r")) {
            throw new GeneralSecurityException("Invalid token");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        AtomicFile file = tokenFile(context);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(cipher.getIV().length);
            stream.write(cipher.getIV());
            stream.write(encrypted);
            file.finishWrite(stream);
        } catch (IOException e) {
            file.failWrite(stream);
            throw e;
        }
        changeGeneration(context);
    }

    public static synchronized String token(Context context) throws GeneralSecurityException, IOException {
        byte[] bytes = tokenFile(context).readFully();
        if (bytes.length < 30 || bytes[0] != 12) {
            throw new GeneralSecurityException("Invalid credential storage");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 13, bytes.length)), StandardCharsets.UTF_8);
    }
}
