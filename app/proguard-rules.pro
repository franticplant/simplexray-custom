-keep class com.simplexray.an.service.TProxyService {
    @kotlin.jvm.JvmStatic *;
}

# protobuf-lite reads message fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
