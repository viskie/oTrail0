package in.otrail.extensions.mod1;
import android.security.keystore.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

final class GitKeys {
  static final String ALIAS="otrail.god.github.ssh";
  static File generate(File data)throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if(!store.containsAlias(ALIAS)){
      KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
      generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN).setKeySize(3072).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build());generator.generateKeyPair();
    }
    RSAPublicKey key=(RSAPublicKey)store.getCertificate(ALIAS).getPublicKey();ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);put(out,"ssh-rsa".getBytes("UTF-8"));put(out,key.getPublicExponent().toByteArray());put(out,key.getModulus().toByteArray());
    File pub=new File(data,"GOD-github.pub");StudioFiles.write(pub,("ssh-rsa "+Base64.getEncoder().encodeToString(bytes.toByteArray())+" oTraiL-GOD\n").getBytes("UTF-8"));return pub;
  }
  private static void put(DataOutputStream out,byte[] data)throws IOException{out.writeInt(data.length);out.write(data);}
}
