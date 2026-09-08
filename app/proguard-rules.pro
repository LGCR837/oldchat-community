# Android 2.3 的旧 Dalvik 无法可靠校验 R8 横向/纵向类合并后的字节码。
# 线上日志已确认 AppCompatColorStateListInflater 被合并进业务模型后触发 VerifyError。
# 继续保留无用代码裁剪与名称混淆，但关闭会改变类结构和指令形态的优化。
-dontoptimize

# Support Library 24.2.1 是最后支持 API 9 的版本，禁止 R8 将其类与业务类合并。
-keep,allowshrinking,allowobfuscation class android.support.** { *; }
# API 9/10 通过反射开启 vector compat；类名和方法名不能被 R8 改写。
-keep class android.support.v7.app.AppCompatDelegate {
    public static void setCompatVectorFromResourcesEnabled(boolean);
}

# LuaJ 会通过 LuaValue.load() 和内部工厂间接创建大量嵌套类。
# Release 的 R8 无法静态识别这些引用，裁剪后会出现 Bit32LibV 等类缺失。
-keep class org.luaj.vm2.** { *; }
-keep interface org.luaj.vm2.** { *; }
-dontwarn org.luaj.vm2.**

# 保留 Lua 脚本回调桥接使用的匿名函数签名。
-keepattributes InnerClasses,EnclosingMethod,Signature

# 旧视(B站)模块：Gson 通过反射按字段名/@SerializedName 注解解析 JSON。
# 不 keep 会导致 R8 把模型字段全部裁剪、类变成空壳/抽象类，
# 运行时抛 "Unable to invoke no-args constructor"，推荐流/详情/评论全部加载失败。
# 保留注解是 Gson 按 JSON 字段名映射的前提，保留类与成员则防止字段被 shrink。
-keepattributes *Annotation*
-keep class com.im.oldchat.bili.** { *; }
-keepclassmembers class com.im.oldchat.bili.** { *; }
-dontwarn com.im.oldchat.bili.**

# Android 2.3/4.x 通过反射安装 Conscrypt，以连接仅支持 TLS 1.2+ 的正式服务器。
-keep class org.conscrypt.** { *; }
-keep interface org.conscrypt.** { *; }
# SpongyCastle 全量保留且不混淆：provider 构造时按类名反射注册 EC/ECDH 实现，
# 任何类被混淆或收缩删除都会导致 NoSuchAlgorithmException: KeyPairGenerator EC not found。
# 不用 allowshrinking（会收缩 provider 反射引用的类），全量 keep + -dontwarn 压 LDAP。
-keep class org.spongycastle.** { *; }
-keep interface org.spongycastle.** { *; }
-dontwarn org.spongycastle.x509.**
-dontwarn javax.naming.**
-dontwarn org.conscrypt.**
