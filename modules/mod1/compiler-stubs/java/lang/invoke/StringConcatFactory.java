package java.lang.invoke;
/** Compile-only bootstrap signature; D8 desugars it. Never packaged in the APK DEX. */
public final class StringConcatFactory {
 public static CallSite makeConcat(MethodHandles.Lookup caller,String name,MethodType type){return null;}
 public static CallSite makeConcatWithConstants(MethodHandles.Lookup caller,String name,MethodType type,String recipe,Object... constants){return null;}
}
