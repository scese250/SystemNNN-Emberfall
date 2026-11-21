# 构建与 TYN 接入

以下命令均在仓库根目录执行。

## Android APK


在 Windows 的仓库根目录构建 Android APK。需要 Python 3.9 或以上、JDK 17、Android SDK Platform 36、Build Tools 36.0.0 和 `curl.exe`。WSL 中需要 CMake、GNU Make、`flock`（util-linux）和 Linux 版 Android NDK r28。按安装位置填写以下路径。

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-17'
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
$env:SYSTEMNNN_NDK = '/opt/android-ndk-r28'
python platform/android/build_player.py
```

脚本默认使用 Ubuntu WSL 发行版，可通过 `SYSTEMNNN_WSL_DISTRO` 指定其他发行版。首次构建会下载 FFmpeg 源码并交叉编译。默认输出为 `build/tyranor/SystemNNN-Emberfall-0.8.1-arm64.apk`。加上 `--movie-codec-linkage static` 可在本地生成供提取单个 `.so` 使用的 `build/tyranor/SystemNNN-Emberfall-0.8.1-single-arm64.apk`。默认使用仓库内的固定调试密钥 `platform/android/signing/emberfall-debug.p12` 签名，因此本地与未配置 Secrets 的 GitHub Actions 构建之间证书一致，可直接覆盖安装（从早期随机密钥版本升级需先卸载一次）。如需使用私有签名密钥，可通过 `--keystore` / `--keystore-pass` / `--key-alias` / `--key-pass` 或环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`（可在 GitHub Secrets 中配置）覆盖；如需递增 `versionCode`，可通过 `--version-code` 或 `ANDROID_VERSION_CODE` 指定（不可低于 `AndroidManifest.xml` 中的值）。

FFmpeg 解压完成后才会发布可复用的源码目录。升级旧构建缓存时，未标记为完整的源码树会移入同级的 `source-previous.*` 目录。

在 Linux 或 WSL 中构建主机工具和测试。

```sh
cmake -S platform/scene -B build/scene-host-debug -DCMAKE_BUILD_TYPE=Debug
cmake --build build/scene-host-debug --parallel 4
(cd build/scene-host-debug && ctest --output-on-failure)
```

单独构建核心库与探针的命令和公开头文件见 [核心说明](../../core/README.md)。

## 从源码接入 TYN

维护者可按[接入说明](tyn/README.md)将宿主与运行库合入 TYN 的 `:engine`，再合并游戏识别、启动和存档入口。接入包保留原 Java 包名与 JNI 约定。更新后的独立 APK 打开原游戏目录时，会将本应用的旧私有存档复制到游戏的 `sav`，供有该目录读写权限的内置版使用；TYN 本身不能直接读取另一个应用的私有目录。

```powershell
python platform/android/package_tyn.py --layout split
python platform/android/package_tyn.py --layout single
```

先构建对应布局的 APK，再执行本地导出。输出包含宿主源码、配置合并片段、引擎构建源码及 FFmpeg 源码。
