# Regras de otimização para APK pequeno

# OkHttp / Okio / Conscrypt
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Manter classes do próprio app referenciadas por reflexão (layouts, etc.)
-keep class app.tvdigital.ativador.** { *; }

# Remover chamadas de log no release (reduz tamanho + esconde debug)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
