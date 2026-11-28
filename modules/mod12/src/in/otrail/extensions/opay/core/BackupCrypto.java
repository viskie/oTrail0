package in.otrail.extensions.opay.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for financial backups. PBKDF2-HMAC-SHA256 derives the key
 * from a user passphrase; AES-256-GCM detects tampering. Passphrases are never stored.
 */
public final class BackupCrypto {
  private static final byte[] MAGIC = {'O', 'P', 'A', 'Y', '1'};
  private static final int SALT = 16, IV = 12, TAG_BITS = 128, ITERATIONS = 120_000;

  private BackupCrypto() {}

  public static byte[] encrypt(byte[] plain, char[] passphrase) throws GeneralSecurityException, IOException {
    if (passphrase == null || passphrase.length < 8)
      throw new GeneralSecurityException("Use a passphrase of at least 8 characters");
    byte[] salt = new byte[SALT];
    byte[] iv = new byte[IV];
    SecureRandom random = new SecureRandom();
    random.nextBytes(salt);
    random.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(MAGIC);
    byte[] ciphertext = cipher.doFinal(plain);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.write(MAGIC);
    out.writeInt(salt.length);
    out.write(salt);
    out.writeInt(iv.length);
    out.write(iv);
    out.writeInt(ciphertext.length);
    out.write(ciphertext);
    out.flush();
    return bytes.toByteArray();
  }

  public static byte[] decrypt(byte[] data, char[] passphrase) throws GeneralSecurityException, IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
    byte[] magic = new byte[MAGIC.length];
    in.readFully(magic);
    for (int index = 0; index < MAGIC.length; index++)
      if (magic[index] != MAGIC[index]) throw new GeneralSecurityException("Not an oPay backup");
    byte[] salt = read(in, 64);
    byte[] iv = read(in, 32);
    byte[] ciphertext = read(in, 128 * 1024 * 1024);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(MAGIC);
    return cipher.doFinal(ciphertext);
  }

  public static String plainJson(String json) {
    return json == null ? "" : new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  private static byte[] read(DataInputStream in, int maximum) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > maximum) throw new IOException("Corrupt backup header");
    byte[] value = new byte[length];
    in.readFully(value);
    return value;
  }

  private static SecretKeySpec key(char[] passphrase, byte[] salt) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
    try {
      byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      return new SecretKeySpec(raw, "AES");
    } finally {
      spec.clearPassword();
    }
  }
}
