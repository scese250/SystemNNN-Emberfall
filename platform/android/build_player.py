"""Build the TYN runtime APK from this checkout with the installed Windows SDK and WSL NDK, or natively on Linux."""
import os
import sys
import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from xml.etree import ElementTree

IS_LINUX = sys.platform.startswith("linux")

ANDROID = Path(__file__).resolve().parent
ROOT = ANDROID.parents[1]
PLAYER = ANDROID / "player"
OUTPUT = ROOT / "build/tyranor"
PACKAGE = "com.tyranor.runtime.systemnnn"
MOVIE_CODEC_VERSION = "8.1.3"
MOVIE_CODEC_LIBS = ("avformat", "avcodec", "swscale", "swresample", "avutil")
FFMPEG_SOURCE = f"ffmpeg-{MOVIE_CODEC_VERSION}-source.tar.gz"

def apk_filename(version, linkage):
    suffix = "-single" if linkage == "static" else ""
    return f"SystemNNN-Emberfall-{version}{suffix}-arm64.apk"

def run(args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)

def linux(path):
    return Path("/mnt") / path.drive[0].lower() / path.as_posix()[3:]

def target_path(path):
    if IS_LINUX:
        return path.as_posix()
    return linux(path).as_posix()

def prepare_movie_codec(wsl, ndk, linkage):
    archive = OUTPUT / FFMPEG_SOURCE
    if not archive.is_file():
        partial = archive.with_suffix(".partial")
        url = f"https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n{MOVIE_CODEC_VERSION}"
        curl_bin = "curl" if IS_LINUX else "curl.exe"
        run([curl_bin, "-fL", "--retry", "2", "--connect-timeout", "15", "--max-time", "180",
             "-o", partial, url])
        partial.replace(archive)
    suffix = "-static" if linkage == "static" else ""
    codec = OUTPUT / "movie-codec-arm64" / (MOVIE_CODEC_VERSION + suffix)
    prefix = target_path(codec)
    run(wsl + ["bash", target_path(ANDROID / "build_movie_codec.sh"),
               target_path(archive), ndk, prefix, linkage])
    return codec

def find_ndk(wsl, sdk):
    ndk = os.getenv("SYSTEMNNN_NDK") or os.getenv("ANDROID_NDK_HOME") or os.getenv("ANDROID_NDK_ROOT")
    if ndk:
        return ndk
    if IS_LINUX:
        ndk_parent = sdk / "ndk"
        if ndk_parent.is_dir():
            ndk_dirs = sorted(ndk_parent.iterdir(), reverse=True)
            for d in ndk_dirs:
                if (d / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy").is_file():
                    return str(d)
        distro_home = os.environ.get("HOME", "")
        fallback = distro_home + "/.cache/systemnnn-m/toolchains/android-ndk-r28"
        return fallback
    else:
        distro_home = subprocess.check_output(wsl + ["printenv", "HOME"], encoding="utf-8").strip()
        return distro_home + "/.cache/systemnnn-m/toolchains/android-ndk-r28"

def main():
    manifest = PLAYER / "AndroidManifest.xml"
    version = ElementTree.parse(manifest).getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--movie-codec-linkage", choices=("shared", "static"), default="shared",
                        help="link FFmpeg as separate shared libraries or embed it in libsystemnnn.so")
    parser.add_argument("--output", type=Path, help="override the versioned APK output path")
    args = parser.parse_args()
    linkage = args.movie_codec_linkage

    ext = "" if IS_LINUX else ".exe"

    if "LOCALAPPDATA" in os.environ:
        local = Path(os.environ["LOCALAPPDATA"]) / "CodexTools/SystemNNN-M"
    elif "RUNNER_TEMP" in os.environ:
        local = Path(os.environ["RUNNER_TEMP"]) / "SystemNNN-Emberfall"
    else:
        local = Path.home() / ".local/share/SystemNNN-Emberfall"

    sdk_env = os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME")
    if sdk_env:
        sdk = Path(sdk_env)
    elif IS_LINUX:
        sdk = Path.home() / "Android/Sdk"
    else:
        sdk = local / "android-sdk"

    java_home_env = os.getenv("JAVA_HOME")
    if java_home_env:
        java_home = Path(java_home_env)
    elif IS_LINUX:
        java_home = None
    else:
        java_home = Path.home() / ".gradle/jdks/eclipse_adoptium-17-amd64-windows.2"

    bt = sdk / "build-tools/36.0.0"
    if not bt.exists() and (sdk / "build-tools").exists():
        installed_bt = sorted((sdk / "build-tools").iterdir(), reverse=True)
        if installed_bt:
            bt = installed_bt[0]

    jar = sdk / "platforms/android-36/android.jar"
    if not jar.exists() and (sdk / "platforms").exists():
        installed_jars = sorted((sdk / "platforms").glob("android-*/android.jar"), reverse=True)
        if installed_jars:
            jar = installed_jars[0]

    if java_home and (java_home / f"bin/java{ext}").is_file():
        java = java_home / f"bin/java{ext}"
        javac = java_home / f"bin/javac{ext}"
        keytool = java_home / f"bin/keytool{ext}"
    else:
        java = Path(shutil.which(f"java{ext}") or "java")
        javac = Path(shutil.which(f"javac{ext}") or "javac")
        keytool = Path(shutil.which(f"keytool{ext}") or "keytool")

    native = OUTPUT / "native-arm64"
    OUTPUT.mkdir(parents=True, exist_ok=True)

    wsl = [] if IS_LINUX else ["wsl", "-d", os.getenv("SYSTEMNNN_WSL_DISTRO", "Ubuntu"), "--"]
    ndk = find_ndk(wsl, sdk)

    codec = prepare_movie_codec(wsl, ndk, linkage)
    run(wsl + ["cmake", "-S", target_path(PLAYER), "-B", target_path(native),
               "-DCMAKE_TOOLCHAIN_FILE=" + ndk + "/build/cmake/android.toolchain.cmake", "-DANDROID_ABI=arm64-v8a",
               "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
               "-DMOVIE_CODEC_ROOT=" + target_path(codec), "-DMOVIE_CODEC_LINKAGE=" + linkage])
    run(wsl + ["cmake", "--build", target_path(native), "--parallel", "6", "--target", "systemnnn"])

    local.mkdir(parents=True, exist_ok=True)
    temp_dir_kwargs = {} if IS_LINUX else {"dir": local}
    with tempfile.TemporaryDirectory(prefix="player-", **temp_dir_kwargs) as tmp:
        stage = Path(tmp)
        packed_lib = native / "libsystemnnn-package.so"
        objcopy_bin = ndk + "/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy"
        run(wsl + [objcopy_bin, "--strip-unneeded",
                   target_path(native / "libsystemnnn.so"), target_path(packed_lib)])
        shutil.copytree(PLAYER / "res", stage / "res")
        shutil.copyfile(manifest, stage / "AndroidManifest.xml")
        licenses = stage / "assets/licenses"
        licenses.mkdir(parents=True)
        for source, name in ((ROOT / "LICENSE", "Emberfall-GPL-2.0-only.txt"),
                             (ROOT / "core/third_party/zlib/LICENSE", "zlib.txt"),
                             (ROOT / "core/third_party/libpng/LICENSE", "libpng.txt"),
                             (codec / "source/COPYING.LGPLv2.1", "ffmpeg-lgpl-2.1.txt"),
                             (ANDROID / "app/assets/licenses/VNTranslationTools-MIT.txt", "VNTranslationTools-MIT.txt")):
            shutil.copyfile(source, licenses / name)
        (licenses / "emberfall-source.txt").write_text(
            "Copyright (c) 2026 PorisRora\n"
            "Emberfall original code: GPL-2.0-only.\n"
            "Source and build instructions: https://github.com/PorisRora/SystemNNN-Emberfall\n",
            encoding="utf-8")
        linked = "statically linked into libsystemnnn.so" if linkage == "static" else "dynamically linked"
        relinking = ("Static relinking: https://github.com/PorisRora/SystemNNN-Emberfall/blob/main/platform/android/tyn/README.md\n"
                     if linkage == "static" else "")
        (licenses / "ffmpeg-source.txt").write_text(
            f"FFmpeg {MOVIE_CODEC_VERSION} (LGPL 2.1 or later) is {linked}.\n"
            f"Unmodified source: https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n{MOVIE_CODEC_VERSION}\n"
            "Build options: platform/android/build_movie_codec.sh (also packaged as ffmpeg-build.sh).\n"
            "Emberfall build instructions: https://github.com/PorisRora/SystemNNN-Emberfall/blob/main/platform/android/BUILDING.md\n"
            + relinking, encoding="utf-8")
        shutil.copyfile(ANDROID / "build_movie_codec.sh", licenses / "ffmpeg-build.sh")
        for source in ("stb_vorbis.c", "stb_image.h"):
            decoder = ROOT / "core/third_party/stb" / source
            terms = decoder.read_text(encoding="utf-8")
            (licenses / (decoder.stem + ".txt")).write_text(terms[terms.rindex("ALTERNATIVE A"):].removesuffix("*/\n"), encoding="utf-8")
        shutil.copytree(PLAYER / "java", stage / "java")
        shutil.copyfile(ANDROID / "app/java/com/tyranor/runtime/systemnnn/SjisTunnelDecoder.java", stage / "java/SjisTunnelDecoder.java")
        run([bt / f"aapt2{ext}", "compile", "--dir", "res", "-o", "resources.zip"], stage)
        run([bt / f"aapt2{ext}", "link", "-I", jar, "--manifest", "AndroidManifest.xml", "--auto-add-overlay", "-R", "resources.zip",
             "-A", "assets", "-o", "resources.apk"], stage)
        classes = stage / "classes"
        classes.mkdir()
        run([javac, "-encoding", "UTF-8", "--release", "8", "-classpath", jar,
             "-d", classes, *sorted((stage / "java").rglob("*.java"))], stage)
        # One archive keeps the D8 command line within the command length limit.
        with zipfile.ZipFile(stage / "classes.jar", "w") as bundle:
            for item in sorted(classes.rglob("*.class")):
                bundle.write(item, item.relative_to(classes).as_posix())
        run([java, "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "26",
             "--lib", jar, "--output", "classes.zip", "classes.jar"], stage)
        with zipfile.ZipFile(stage / "resources.apk") as resources, zipfile.ZipFile(stage / "player.apk", "w") as apk, zipfile.ZipFile(stage / "classes.zip") as dex:
            for item in resources.infolist():
                apk.writestr(item.filename, resources.read(item), item.compress_type)
            apk.writestr("classes.dex", dex.read("classes.dex"), zipfile.ZIP_DEFLATED)
            apk.write(packed_lib, "lib/arm64-v8a/libsystemnnn.so", zipfile.ZIP_STORED)
            if linkage == "shared":
                for component in MOVIE_CODEC_LIBS:
                    lib = f"lib{component}.so"
                    apk.write(codec / "lib" / lib, "lib/arm64-v8a/" + lib, zipfile.ZIP_STORED)
        run([bt / f"zipalign{ext}", "-P", "16", "-f", "4", "player.apk", "aligned.apk"], stage)
        key = local / "debug-signing/emberfall-debug.p12"
        if not key.is_file():
            key.parent.mkdir(parents=True, exist_ok=True)
            run([keytool, "-genkeypair", "-keystore", key, "-alias", "emberfall-debug",
                 "-storepass", "android", "-keypass", "android", "-dname", "CN=Emberfall Local Debug",
                 "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-noprompt"])
        signer = [java, "-jar", bt / "lib/apksigner.jar"]
        run(signer + ["sign", "--ks", key, "--ks-key-alias", "emberfall-debug", "--ks-pass", "pass:android",
                      "--key-pass", "pass:android", "--v1-signing-enabled", "false", "--out", "signed.apk", "aligned.apk"], stage)
        run(signer + ["verify", "--verbose", "signed.apk"], stage)
        run([bt / f"zipalign{ext}", "-c", "-P", "16", "4", "signed.apk"], stage)
        final = (args.output or OUTPUT / apk_filename(version, linkage)).resolve()
        final.parent.mkdir(parents=True, exist_ok=True)
        pending = final.with_suffix(".tmp")
        shutil.copyfile(stage / "signed.apk", pending)
        pending.replace(final)
        print(final)

if __name__ == "__main__":
    main()
