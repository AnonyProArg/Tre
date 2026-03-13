# Reglas enfocadas en minimizar tamaño sin romper serialización/reflexión del SDK externo.
-allowaccessmodification
-repackageclasses

# Quita llamadas de logging en release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}
