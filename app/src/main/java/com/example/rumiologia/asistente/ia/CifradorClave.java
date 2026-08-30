package com.example.rumiologia.asistente.ia;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cifra y descifra texto con una llave AES-256-GCM del Android Keystore.
 *
 * <p>La llave se genera una sola vez, vive dentro del almacén seguro del dispositivo
 * y no se puede exportar en texto plano: ni esta clase ni nadie puede leerla, solo
 * usarla para cifrar/descifrar. Por eso lo único que llega a disco (o a Supabase, si
 * se activa la copia en la nube) es el par cifrado+iv, nunca la clave de Gemini en
 * claro.
 */
final class CifradorClave {

    private static final String ALIAS = "rumiologia_clave_api";
    private static final String PROVEEDOR = "AndroidKeyStore";
    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final int TAMANO_TAG_BITS = 128;

    /** Par cifrado+iv, ambos en Base64, listos para guardar o transportar. */
    static final class Resultado {
        final String cifradoBase64;
        final String ivBase64;

        Resultado(String cifradoBase64, String ivBase64) {
            this.cifradoBase64 = cifradoBase64;
            this.ivBase64 = ivBase64;
        }
    }

    @NonNull
    Resultado cifrar(@NonNull String texto) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMACION);
        cipher.init(Cipher.ENCRYPT_MODE, obtenerOCrearLlave());

        byte[] cifrado = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        String cifradoBase64 = Base64.encodeToString(cifrado, Base64.NO_WRAP);
        String ivBase64 = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        return new Resultado(cifradoBase64, ivBase64);
    }

    @NonNull
    String descifrar(@NonNull String cifradoBase64, @NonNull String ivBase64) throws Exception {
        byte[] cifrado = Base64.decode(cifradoBase64, Base64.NO_WRAP);
        byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance(TRANSFORMACION);
        cipher.init(Cipher.DECRYPT_MODE, obtenerOCrearLlave(),
                new GCMParameterSpec(TAMANO_TAG_BITS, iv));

        byte[] texto = cipher.doFinal(cifrado);
        return new String(texto, StandardCharsets.UTF_8);
    }

    /** Reutiliza la llave del Keystore si ya existe; la genera solo la primera vez. */
    private SecretKey obtenerOCrearLlave() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PROVEEDOR);
        keyStore.load(null);

        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }

        KeyGenerator generador =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generador.init(spec);
        return generador.generateKey();
    }
}
