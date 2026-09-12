package cn.solarmoon.spark_core.physics;

import cn.solarmoon.spark_core.SparkCore;
import com.jme3.bullet.util.NativeLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class NativePhysicsLoader {
    public static final String VERSION = "22.0.3";
    private static final String LINUX_SHA256 = "675cf1a488f07a42fdabb6d72a7ff529e37b90ef687a4809a2d99d6f077d5b63";
    private static final String WINDOWS_SHA256 = "ff91c048b18c2c31bc80616d9294940cf8130a2622b7323b7950e632ca9ac0b1";
    private static boolean loaded;

    private NativePhysicsLoader() {
    }

    public static synchronized void load() {
        if (loaded) return;

        PlatformNative platform = detectPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
        byte[] bytes;
        ClassLoader loader = NativePhysicsLoader.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(platform.resourcePath())) {
            if (stream == null) {
                throw new IllegalStateException("Spark Core 缺少 Libbulletjme 原生库资源: " + platform.resourcePath());
            }
            bytes = stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Libbulletjme 原生库失败", exception);
        }

        String actualHash = sha256(bytes);
        if (!platform.sha256().equals(actualHash)) {
            throw new IllegalStateException("Libbulletjme 原生库校验失败: expected=" + platform.sha256() + ", actual=" + actualHash);
        }

        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "spark_core-natives", VERSION, actualHash);
            Files.createDirectories(directory);
            Path nativeFile = directory.resolve(platform.fileName());
            if (!Files.isRegularFile(nativeFile) || !sha256(Files.readAllBytes(nativeFile)).equals(actualHash)) {
                Path staging = Files.createTempFile(directory, "extract-", ".tmp");
                Files.write(staging, bytes);
                try {
                    Files.move(staging, nativeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    Files.move(staging, nativeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.load(nativeFile.toAbsolutePath().toString());
            String nativeVersion = NativeLibrary.versionNumber();
            if (!VERSION.equals(nativeVersion)) {
                throw new IllegalStateException("Libbulletjme 版本不匹配: expected=" + VERSION + ", actual=" + nativeVersion);
            }
            if (!NativeLibrary.isThreadSafe()) {
                throw new IllegalStateException("Libbulletjme 必须使用线程安全的 SpMtRelease 构建");
            }
            loaded = true;
            SparkCore.LOGGER.info("Loaded Libbulletjme {} from {} ({})", nativeVersion, platform.resourcePath(), actualHash);
        } catch (IOException | UnsatisfiedLinkError exception) {
            throw new IllegalStateException("加载 Libbulletjme 失败: " + platform.resourcePath(), exception);
        }
    }

    static PlatformNative detectPlatform(String osName, String architecture) {
        String os = osName == null ? "" : osName.toLowerCase();
        String arch = architecture == null ? "" : architecture.toLowerCase();
        if (!(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IllegalStateException("Spark Core 首版仅支持 x86_64，当前架构: " + architecture);
        }
        if (os.contains("linux")) {
            return new PlatformNative("natives/linux/x86_64/libbulletjme.so", "libbulletjme.so", LINUX_SHA256);
        }
        if (os.contains("windows")) {
            return new PlatformNative("natives/windows/x86_64/bulletjme.dll", "bulletjme.dll", WINDOWS_SHA256);
        }
        throw new IllegalStateException("Spark Core 首版仅支持 Windows/Linux x86_64，当前系统: " + osName);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    record PlatformNative(String resourcePath, String fileName, String sha256) {
    }
}
