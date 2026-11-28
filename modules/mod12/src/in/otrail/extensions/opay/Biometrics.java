package in.otrail.extensions.opay;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;

/** Optional device-biometric lock using the platform API (no AndroidX dependency). */
final class Biometrics {
  private Biometrics() {}

  static boolean available(Activity activity) {
    try {
      BiometricManager manager = activity.getSystemService(BiometricManager.class);
      if (manager == null) return false;
      int result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
      return result == BiometricManager.BIOMETRIC_SUCCESS;
    } catch (RuntimeException error) {
      return false;
    }
  }

  static void authenticate(Activity activity, Runnable success, Runnable failure) {
    try {
      BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
          .setTitle("Unlock oPay")
          .setSubtitle("Financial data stays on this phone")
          .setNegativeButton("Cancel", activity.getMainExecutor(),
              (dialog, which) -> failure.run())
          .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
          .build();
      prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
          new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
              success.run();
            }

            @Override public void onAuthenticationError(int code, CharSequence message) {
              failure.run();
            }

            @Override public void onAuthenticationFailed() {
              // Keep the prompt open; the user can retry or cancel.
            }
          });
    } catch (RuntimeException error) {
      failure.run();
    }
  }
}
