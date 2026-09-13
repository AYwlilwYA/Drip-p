# Drip Native Hook API

> 模块开发者文档 · 契约版本 1 · 适用于 Drip 2.4.x
> 本文件是 `com.drip.api.nativehook` 的**对外契约**。

---

## 1. 这是什么

除 Java 方法 hook（libxposed / legacy）之外，Drip 另开放一组 **native hook** 能力：**hook 已加载动态库中的导出符号**（例如 `libc.so` 里的某个函数）。

该能力**与 libxposed / legacy API 相互独立**：

- 不使用它的模块**完全不受影响**，无需任何声明；
- 使用它的模块需要在 APK 内**额外声明一个入口文件**（见 §3）；
- 模块**不需要自带任何 native 库** —— hook 引擎由框架提供，本 API 就是全部使用面。

> ⚠️ **请勿对模块启用代码混淆（R8 / ProGuard）、字符串加密或动态拼名。**
> Drip 的隐藏能力需要模块与框架协同工作；模块自行混淆后，**Drip 将无法为其提供完整的隐藏保护**。

---

## 2. 编译依赖

API 的类型只在**编译期**需要；运行期的实现由 Drip 框架在目标进程中提供，**不打包进模块 APK**。

在本仓库 `api/` 目录获取 stub jar（`drip-native-api.jar`）：

```kotlin
dependencies {
    compileOnly(files("<path>/drip-native-api.jar"))
}
```

`compileOnly` 确保 stub 只参与编译，不进入 APK。

> 📌 stub jar 与框架版本对应 —— 升级 Drip 后请同步替换 `api/` 下的 jar。

---

## 3. 入口声明

模块若要使用 native hook，必须在 APK 内放置**独立入口文件**：

```
Xposed/nativeHook
```

文件内容为**入口类的全限定名**，例如：

```
com.example.MyModule
```

要求：

- 路径固定为 `Xposed/nativeHook`，不得更改；
- 内容为入口类全限定名，**单行**；
- 入口类必须 `public`、实现 `com.drip.api.nativehook.DripNativeHookModule`，且**有可访问的无参构造**；
- 未声明该文件的模块，Drip 不会为其注入 native hook 能力（Java hook 功能不受影响）。

**回调时机**：框架完成该模块的常规装载之后回调一次 `onNativeHookAttached(DripNativeHook)`。
框架只保证回调发生，**不保证**它与 `onModuleLoaded` 的先后顺序可供依赖 —— 请把 hook 安装逻辑写在这个回调里，不要依赖外部状态。

---

## 4. 快速开始

**入口类**：

```java
package com.example;

import com.drip.api.nativehook.*;

public class MyModule implements DripNativeHookModule {

    @Override
    public void onNativeHookAttached(DripNativeHook hook) {
        // 例：hook libc.so 的 getpid() —— 返回类型 i32，无参数
        hook.hookSymbol(
            "libc.so",
            "getpid",
            NativeSignature.of(NativeSignature.I32),
            param -> {
                Long original = (Long) param.callOriginal();  // 调原函数拿真实值
                return original;                              // 不改行为，仅观察
            });
    }
}
```

**入口文件** `Xposed/nativeHook`：

```
com.example.MyModule
```

---

## 5. 包结构

包名 `com.drip.api.nativehook`，共 8 个公开类型：

```
com.drip.api.nativehook
├── DripNativeHookModule        // 模块入口接口
├── DripNativeHook              // 操作接口
├── NativeCallback              // 回调接口
├── NativeParam                 // 回调参数
├── HookHandle                  // hook 句柄
├── NativeSignature             // 签名常量与构造工具
├── HookPriority                // 优先级常量
└── NativeHookException         // 异常
```

以下所有**包名、类名、方法名、字段名、常量值均为稳定契约**。

---

## 6. API 参考

### 6.1 `DripNativeHookModule`

模块入口类必须实现此接口。

```java
package com.drip.api.nativehook;

public interface DripNativeHookModule {
    /**
     * 框架在模块装载完成后调用，注入绑定该模块身份的 native hook 实例。
     *
     * @param hook 绑定当前模块的 native hook 实例（该模块只能通过它操作自己的 hook）
     */
    void onNativeHookAttached(DripNativeHook hook);
}
```

### 6.2 `DripNativeHook`

Native hook 操作接口。实例由框架创建并**绑定到当前模块**，模块只能操作自己的 hook。

```java
package com.drip.api.nativehook;

import java.lang.reflect.Method;

public interface DripNativeHook {

    /** Hook 指定已加载库中的导出符号。 */
    HookHandle hookSymbol(String libName, String symbolName, String signature,
                          NativeCallback callback);

    /** 同上一方法，并指定优先级。 */
    HookHandle hookSymbol(String libName, String symbolName, String signature,
                          int priority, NativeCallback callback);

    /** Hook 一个 Java native 方法（JNI 方法）。【当前版本未提供】 */
    HookHandle hookJavaNativeMethod(Method method, String signature,
                                    NativeCallback callback);

    /** 按类名 / 方法名 / JNI 描述符 hook 一个 Java native 方法。【当前版本未提供】 */
    HookHandle hookJavaNativeMethod(String className, String methodName, String signature,
                                    NativeCallback callback);

    /** 卸载指定 hook（恢复目标函数的原始行为）。 */
    void unhook(HookHandle handle);

    /** 卸载本模块在当前进程安装的全部 hook。 */
    void unhookAll();
}
```

**`hookSymbol` 参数**

| 参数 | 说明 |
|---|---|
| `libName` | 目标库名，如 `"libtarget.so"`。该库须**已被当前进程加载** |
| `symbolName` | 目标**导出符号**名 |
| `signature` | 函数签名，格式见 §7。**必须与真实 ABI 一致** |
| `priority` | 优先级，见 §6.7 |
| `callback` | 拦截回调 |

**`hookSymbol` 抛出**

| 场景 | 异常 |
|---|---|
| 任一路径参数为 `null` | `NativeHookException("hookSymbol: null argument")` |
| 签名格式不合法 / 含不支持的类型 | `NativeHookException(... "invalid signature syntax")` |
| 参数个数 > 8 | `NativeHookException(... "too many arguments (max 8; stack-passed args are not supported)")` |
| 目标库未加载 | `NativeHookException(... "library not loaded: <lib>")` |
| 符号不存在 | `NativeHookException(... "symbol not found: <lib>!<sym>")` |
| 目标是框架自身所用库 / 底层函数 | `NativeHookException(... "refused: ...")` |
| 本进程 hook 槽位耗尽 | `NativeHookException(... "hook slot exhausted (max 32 per process)")` |
| 底层安装失败 | `NativeHookException(... "DobbyHook failed")` |

**`unhook`**：句柄已失效、或不属于本模块时**为无操作**（不抛异常）。
⚠️ 避免与目标函数的高频执行并发调用。`unhook` 返回 `void` —— **模块无法感知卸载是否成功**。

### 6.3 `NativeCallback`

```java
package com.drip.api.nativehook;

public interface NativeCallback {
    /**
     * Hook 触发时调用。
     *
     * @param param 回调参数（读取 / 修改实参、调用原函数）
     * @return 返回值，按目标函数返回类型构造（见 §7 类型映射）；
     *         目标函数返回 void 时返回 null
     * @throws Throwable 任意异常均被框架捕获记录，不会传播回目标函数
     */
    Object invoke(NativeParam param) throws Throwable;
}
```

**线程模型**：回调在**触发目标函数调用的那个线程上同步执行**。回调内可以调用 Java API，但**禁止长时间阻塞** —— 会直接阻塞目标线程。

**异常行为**：回调抛出的异常由框架捕获并记录，**目标函数按原逻辑正常执行**（调用方拿到真实结果，不受影响）。

**返回值行为**：

- 目标函数返回 `void` → 返回值被忽略；
- 目标函数有返回值、回调返回 `null` → 目标函数收到 **`0`**（`bool` 为 `false`）；
- 回调返回值类型须与签名匹配，否则按 §7 规则折算。

### 6.4 `NativeParam`

```java
package com.drip.api.nativehook;

import java.lang.reflect.Method;

public interface NativeParam {

    /** 获取 this 对象。当前版本恒返回 null。 */
    Object getThisObject();

    /** 获取指定索引的实参（从 0 开始）。索引越界返回 null。 */
    Object getArg(int index);

    /** 获取全部实参。⚠️ 返回的是内部数组本身，不是副本。 */
    Object[] getArgs();

    /** 修改指定索引的实参。 */
    void setArg(int index, Object value);

    /** 以当前（可能已被修改）实参调用原函数。 */
    Object callOriginal();

    /** 以指定实参调用原函数（不改动当前回调可见的实参）。 */
    Object callOriginal(Object... args);

    /** 目标 Java 方法。当前版本恒返回 null。 */
    Method getMethod();

    /** 目标库名（仅 hookSymbol 场景有值，否则 null）。 */
    String getLibName();

    /** 目标符号名（仅 hookSymbol 场景有值，否则 null）。 */
    String getSymbolName();
}
```

**行为细则**

| 方法 | 行为 |
|---|---|
| `getArgs()` | 返回**内部数组本身**。改写它的元素会直接影响后续 `callOriginal()` 的实参 |
| `setArg` | 索引越界抛 `NativeHookException`。**不校验 `value` 的类型**；类型不符会在折算时失败并折算为 `0` |
| `callOriginal()` | 使用**当前**（可能已被 `setArg` 修改）的实参 |
| `callOriginal(Object...)` | 传 `null` 等价于 `callOriginal()`。**实参个数与签名不符时只记录警告，不抛异常**，且只取前 8 个 |
| `callOriginal` | hook 已卸载 / 句柄已失效时返回 `null` |
| `getThisObject` / `getMethod` | 当前版本**恒返回 `null`** |

### 6.5 `HookHandle`

```java
package com.drip.api.nativehook;

public interface HookHandle {

    /** 句柄是否仍然有效（未被 invalidate() 停用、未被 unhook() 卸载）。 */
    boolean isValid();

    /** 手动停用本 hook：此后不再触发回调，目标函数按原逻辑执行。幂等。 */
    void invalidate();
}
```

**`invalidate()` 与 `unhook()` 的差别**

| | `handle.invalidate()` | `hook.unhook(handle)` |
|---|---|---|
| 效果 | **停用**：不再触发回调，目标函数恢复原逻辑 | **卸载**：移除 hook |
| 可恢复 | 否（幂等，无法重新启用） | 否 |
| 代价 | 低 | 较高，且**不应与目标函数的高频执行并发调用** |

### 6.6 `NativeSignature`

函数签名的类型常量与构造工具。

```java
package com.drip.api.nativehook;

public final class NativeSignature {
    public static final String VOID = "void";
    public static final String BOOL = "bool";
    public static final String I8   = "i8";
    public static final String I16  = "i16";
    public static final String I32  = "i32";
    public static final String I64  = "i64";
    public static final String U8   = "u8";
    public static final String U16  = "u16";
    public static final String U32  = "u32";
    public static final String U64  = "u64";
    public static final String F32  = "f32";
    public static final String F64  = "f64";
    public static final String PTR  = "ptr";

    /** 构造签名字符串。 */
    public static String of(String returnType, String... argTypes);
}
```

```java
NativeSignature.of(NativeSignature.I32, NativeSignature.PTR, NativeSignature.U64)
// => "i32 (ptr, u64)"

NativeSignature.of(NativeSignature.VOID)          // => "void ()"
```

### 6.7 `HookPriority`

```java
package com.drip.api.nativehook;

public final class HookPriority {
    public static final int DEFAULT = 0;
    public static final int LOW     = -100;
    public static final int HIGH    = 100;
}
```

> ⚠️ **当前版本 `priority` 参数不影响执行顺序。** 多个模块 hook 同一符号时的叠加顺序**未定义**。传入优先级不会报错，但不会有实际效果。

### 6.8 `NativeHookException`

```java
package com.drip.api.nativehook;

public class NativeHookException extends RuntimeException {
    public NativeHookException(String message);
    public NativeHookException(String message, Throwable cause);
}
```

---

## 7. 签名格式与类型映射

### 格式

```
"<返回类型> (<参数类型>, <参数类型>, ...)"
```

- 参数为空时写作 `"void ()"`；
- 分隔符为**逗号 + 一个空格**（推荐用 `NativeSignature.of(...)` 构造，避免手写错误）；
- 签名内允许自由空白（解析时会去除空格 / 制表符 / 换行）。

### 类型映射

| 签名类型 | 含义 | Java 类型 |
|---|---|---|
| `void` | 无返回 | `null` |
| `bool` | 布尔 | `Boolean` |
| `i8` `i16` `i32` `i64` | 有符号整型 | `Long`（符号扩展） |
| `u8` `u16` `u32` `u64` | 无符号整型 | `Long`（零扩展） |
| `ptr` | 指针 / 地址 | `Long`（地址值，`0` 表示 null） |

**整型统一以 `Long` 传递**（消除 32 / 64 位 ABI 差异），模块自行按需窄化。

> ⚠️ **`f32` / `f64` 两个常量当前版本不可用** —— 传入含浮点类型的签名会导致安装失败（抛 `invalid signature syntax`）。浮点支持见 §9。

### ⚠️ 签名正确性是模块的责任

框架按签名读写目标函数的参数与返回值，**框架无法校验签名是否正确**。

**签名与目标函数真实 ABI 不一致会导致未定义行为（通常表现为目标进程崩溃）。**

👉 建议：优先选用你**能确认签名**的符号；不确定时先用一个只观察、不改行为的回调（如快速开始里的 `getpid` 示例）验证。

---

## 8. 使用限制

1. **目标库必须已被当前进程加载** —— 未加载时抛 `NativeHookException`。本能力不负责加载库。
2. **只能 hook 导出符号** —— 库内部非导出符号（被 strip 掉的）无法定位。
3. **参数个数上限 8**，且**参数与返回值须为整型 / 指针 / 布尔**。浮点参数、经栈传递的参数、按值传递的结构体**当前不支持** —— 安装时直接报错，**不会静默错读**。
4. **每进程 hook 槽位上限 32**（所有模块共用）。
5. **不接受的目标**：Drip 框架自身所用的库，以及若干底层内存 / 动态链接函数（框架拒绝安装并抛 `NativeHookException`）。这是为了不影响框架自身的正常注入。
6. **`hookJavaNativeMethod` 系列当前版本未提供** —— 调用会抛 `NativeHookException`。
7. **作用域**：hook 只在**当前注入进程**内生效，与模块的作用域配置一致。
8. **回调禁止长时间阻塞**（见 §6.3 线程模型）。

---

## 9. 当前版本未提供的能力

| 能力 | 状态 |
|---|---|
| `hookJavaNativeMethod`（hook Java native 方法） | 未提供，调用抛 `NativeHookException` |
| 浮点参数 / 返回（`f32` / `f64`） | 不支持，安装失败 |
| 经栈传递的参数 / 结构体返回 | 不支持，安装失败 |
| hook 库内部非导出符号 | 不支持 |
| `priority` 的实际排序效果 | 未生效（§6.7） |
| 库加载后自动 hook（late hook） | 不支持，需库已加载 |

---

## 10. 模块混淆保留清单

若模块自行启用混淆，必须确保以下名称**原名保留**：

| 类别 | 必须保留的名称 |
|---|---|
| 入口文件 | `Xposed/nativeHook` |
| 入口接口 | `com.drip.api.nativehook.DripNativeHookModule` |
| 入口方法 | `onNativeHookAttached` |
| 操作接口 | `com.drip.api.nativehook.DripNativeHook` |
| 操作方法 | `hookSymbol`、`hookJavaNativeMethod`、`unhook`、`unhookAll` |
| 回调接口 | `com.drip.api.nativehook.NativeCallback` |
| 回调方法 | `invoke` |
| 参数接口 | `com.drip.api.nativehook.NativeParam` |
| 参数方法 | `getThisObject`、`getArg`、`getArgs`、`setArg`、`callOriginal`、`getMethod`、`getLibName`、`getSymbolName` |
| 句柄接口 | `com.drip.api.nativehook.HookHandle` |
| 句柄方法 | `isValid`、`invalidate` |
| 签名类 | `com.drip.api.nativehook.NativeSignature` |
| 签名常量 | `VOID`、`BOOL`、`I8`、`I16`、`I32`、`I64`、`U8`、`U16`、`U32`、`U64`、`F32`、`F64`、`PTR` |
| 优先级类 | `com.drip.api.nativehook.HookPriority` |
| 优先级字段 | `DEFAULT`、`LOW`、`HIGH` |
| 异常类 | `com.drip.api.nativehook.NativeHookException` |

参考 R8 / ProGuard 配置：

```proguard
-keep class com.drip.api.nativehook.** { *; }
-keep class * implements com.drip.api.nativehook.DripNativeHookModule { *; }
-keep class * implements com.drip.api.nativehook.NativeCallback { *; }
```

> 再次提醒：**推荐模块完全不启用混淆**（见 §1）。上面的规则只保证 API 调用链不被破坏，**不能**替代「不混淆」带来的完整隐藏保护。

---

## 11. 完整示例

```java
package com.example;

import com.drip.api.nativehook.*;

public class MyModule implements DripNativeHookModule {

    private DripNativeHook hook;
    private HookHandle handle;

    @Override
    public void onNativeHookAttached(DripNativeHook hook) {
        this.hook = hook;

        // 观察型 hook：不改行为，只记录一次调用
        handle = hook.hookSymbol(
            "libtarget.so",
            "target_func",
            NativeSignature.of(NativeSignature.I32, NativeSignature.PTR),
            param -> {
                Long arg0 = (Long) param.getArg(0);   // 整型统一为 Long
                android.util.Log.i("MyModule", "target_func called, arg0=" + arg0);
                return param.callOriginal();          // 原样返回真实结果
            });
    }

    /** 模块自行决定何时卸载。 */
    public void stop() {
        if (handle != null && handle.isValid()) {
            hook.unhook(handle);
        }
    }
}
```

`Xposed/nativeHook`：

```
com.example.MyModule
```

---

## 12. 备注

- 本 API 为 **Drip 扩展能力**，独立于 libxposed API 82 / 93 / 100 / 101 / 102；
- **官方 libXposed 模块不受影响**，无需声明 `Xposed/nativeHook`；
- 文档中列出的所有名称均为**稳定契约**，模块可安全依赖；
- 文档未列出的行为均为实现细节，**不构成契约**。
