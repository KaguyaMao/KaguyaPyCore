# Libbulletjme 集成

## 版本与制品

- Java wrapper：`com.github.stephengold:Libbulletjme-Windows64:22.0.3` 的主 JAR。不同平台主 JAR 的 Java 类相同；名称不代表运行时只能用于 Windows。
- Linux 原生：`Libbulletjme-Linux64:22.0.3:SpMtRelease`。
- Windows 原生：`Libbulletjme-Windows64:22.0.3:SpMtRelease`。

`SpMtRelease` 是线程安全构建。加载后还会用 `NativeLibrary.isThreadSafe()` 验证，避免误把单线程 native 放入每 Level 多线程环境。

## 构建布局

`extractLibbulletNatives` 将上游原生制品规范化到：

```text
natives/linux/x86_64/libbulletjme.so
natives/windows/x86_64/bulletjme.dll
```

`extractLibbulletClasses` 将 wrapper 类作为额外 main classes 输出目录。这样既能进入最终单体模组 JAR，也能被 ForgeGradle 开发环境的 ModLauncher transforming module 看见。仅把依赖声明为普通 `implementation` 会留在 bootstrap classpath，Mixin 变换时解析 `PhysicsTickListener` 仍可能失败。

## 运行时加载

`NativePhysicsLoader`：

1. 规范化 `os.name` 和 `os.arch`；
2. 仅接受 Windows/Linux 的 `amd64` 或 `x86_64`；
3. 从模组资源读取二进制并校验固定 SHA-256；
4. 释放到系统临时目录下的版本/哈希隔离目录；
5. 原子替换损坏或不完整的缓存文件；
6. 使用绝对路径 `System.load`；
7. 校验 native 版本 `22.0.3` 和线程安全标记。

当前哈希：

- Linux：`675cf1a488f07a42fdabb6d72a7ff529e37b90ef687a4809a2d99d6f077d5b63`
- Windows：`ff91c048b18c2c31bc80616d9294940cf8130a2622b7323b7950e632ca9ac0b1`

替换上游版本时必须同时更新 wrapper、两平台 native、版本常量、哈希、JUnit 和 GameTest，不能只改 Maven 版本号。

## 发布边界

最终 Spark Core JAR 内嵌 wrapper 与 native；Kotlin for Forge 仍是正常 Forge 前置。第三方许可见根目录 `THIRD_PARTY_NOTICES.md`。
