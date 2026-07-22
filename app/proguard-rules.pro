# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── DailyNet: 릴리즈 난독화로 서버 통신/Firestore JSON 키가 깨지지 않도록 보호 ──
# Retrofit(Gson)로 서버에 보내는 요청/응답, Firestore에 저장/복원되는 데이터 모델의
# 필드명이 난독화되면 서버가 필드를 못 읽어 분석이 실패하므로 필드명을 유지한다.
-keep class com.youngs.dailynet.data.model.** { *; }

# Gson 직렬화 관련 속성 및 @SerializedName 필드 유지
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin 메타데이터 유지 (리플렉션 기반 직렬화 안정성)
-keep class kotlin.Metadata { *; }