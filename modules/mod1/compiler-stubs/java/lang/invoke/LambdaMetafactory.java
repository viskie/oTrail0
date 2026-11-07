package java.lang.invoke;
/** Compile-only bootstrap signature; D8 desugars it. Never packaged in the APK DEX. */
public final class LambdaMetafactory {
 public static final int FLAG_SERIALIZABLE=1,FLAG_MARKERS=2,FLAG_BRIDGES=4;
 public static CallSite metafactory(MethodHandles.Lookup caller,String name,MethodType type,MethodType sam,MethodHandle implementation,MethodType instantiated){return null;}
 public static CallSite altMetafactory(MethodHandles.Lookup caller,String name,MethodType type,Object... arguments){return null;}
}
