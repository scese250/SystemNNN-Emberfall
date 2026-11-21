"""Build the TYN runtime APK from this checkout with the installed Windows SDK and WSL NDK, or natively on Linux."""
import os
import sys
import argparse
import base64
import binascii
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
DEFAULT_DEBUG_KEYSTORE = ANDROID / "signing/emberfall-debug.p12"
DEFAULT_DEBUG_KEYSTORE_ALIAS = "emberfall-debug"
DEFAULT_DEBUG_KEYSTORE_PASS = "android"
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

def resolve_signing_spec(args):
    env_keystore_path = (os.getenv("ANDROID_KEYSTORE_PATH") or "").strip()
    env_keystore_b64 = (os.getenv("ANDROID_KEYSTORE_BASE64") or "").strip()
    ks_pass = args.keystore_pass if args.keystore_pass is not None else os.getenv("ANDROID_KEYSTORE_PASSWORD", "")
    key_alias = args.key_alias if args.key_alias is not None else (os.getenv("ANDROID_KEY_ALIAS") or "").strip()
    key_pass_raw = args.key_pass if args.key_pass is not None else os.getenv("ANDROID_KEY_PASSWORD", "")

    if args.keystore is not None or env_keystore_path or env_keystore_b64:
        ks_bytes = None
        ks_path = None
        if args.keystore is not None:
            ks_path = args.keystore.expanduser().resolve()
            if not ks_path.is_file():
                raise SystemExit(f"Signing keystore not found: {ks_path}")
            desc = f"custom keystore ({ks_path})"
        elif env_keystore_path:
            ks_path = Path(env_keystore_path).expanduser().resolve()
            if not ks_path.is_file():
                raise SystemExit(f"ANDROID_KEYSTORE_PATH not found: {ks_path}")
            desc = f"ANDROID_KEYSTORE_PATH ({ks_path})"
        else:
            try:
                ks_bytes = base64.b64decode("".join(env_keystore_b64.split()), validate=True)
            except (binascii.Error, ValueError) as exc:
                raise SystemExit("Invalid ANDROID_KEYSTORE_BASE64: failed to decode base64") from exc
            if not ks_bytes:
                raise SystemExit("Invalid ANDROID_KEYSTORE_BASE64: decoded keystore is empty")
            desc = "ANDROID_KEYSTORE_BASE64"

        if not ks_pass:
            raise SystemExit(
                "Keystore password is required when using a custom keystore "
                "(set --keystore-pass or ANDROID_KEYSTORE_PASSWORD)"
            )
        if not key_alias:
            raise SystemExit(
                "Key alias is required when using a custom keystore "
                "(set --key-alias or ANDROID_KEY_ALIAS)"
            )
        return {
            "ks_path": ks_path,
            "ks_bytes": ks_bytes,
            "ks_pass": ks_pass,
            "key_alias": key_alias,
            "key_pass": key_pass_raw if key_pass_raw else ks_pass,
            "desc": desc,
        }

    if DEFAULT_DEBUG_KEYSTORE.is_file():
        return {
            "ks_path": DEFAULT_DEBUG_KEYSTORE,
            "ks_bytes": None,
            "ks_pass": DEFAULT_DEBUG_KEYSTORE_PASS,
            "key_alias": DEFAULT_DEBUG_KEYSTORE_ALIAS,
            "key_pass": DEFAULT_DEBUG_KEYSTORE_PASS,
            "desc": f"committed debug keystore ({DEFAULT_DEBUG_KEYSTORE.relative_to(ROOT).as_posix()})",
        }

    return {
        "ks_path": None,
        "ks_bytes": None,
        "ks_pass": DEFAULT_DEBUG_KEYSTORE_PASS,
        "key_alias": DEFAULT_DEBUG_KEYSTORE_ALIAS,
        "key_pass": DEFAULT_DEBUG_KEYSTORE_PASS,
        "desc": "local generated debug keystore",
    }

def main():
    manifest = PLAYER / "AndroidManifest.xml"
    manifest_root = ElementTree.parse(manifest).getroot()
    android_ns = "{http://schemas.android.com/apk/res/android}"
    version = manifest_root.attrib[android_ns + "versionName"]
    manifest_version_code = int(manifest_root.attrib[android_ns + "versionCode"])

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--movie-codec-linkage", choices=("shared", "static"), default="shared",
                        help="link FFmpeg as separate shared libraries or embed it in libsystemnnn.so")
    parser.add_argument("--output", type=Path, help="override the versioned APK output path")
    parser.add_argument("--version-code", type=int, default=None,
                        help="override android:versionCode (must be >= manifest versionCode)")
    parser.add_argument("--keystore", type=Path, default=None,
                        help="signing keystore path (overrides ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_BASE64)")
    parser.add_argument("--keystore-pass", default=None,
                        help="signing keystore password (defaults to ANDROID_KEYSTORE_PASSWORD)")
    parser.add_argument("--key-alias", default=None,
                        help="signing key alias (defaults to ANDROID_KEY_ALIAS)")
    parser.add_argument("--key-pass", default=None,
                        help="signing key password (defaults to ANDROID_KEY_PASSWORD or keystore password)")
    args = parser.parse_args()
    linkage = args.movie_codec_linkage

    version_code_env = (os.getenv("ANDROID_VERSION_CODE") or "").strip()
    if args.version_code is not None:
        version_code = args.version_code
    elif version_code_env:
        try:
            version_code = int(version_code_env)
        except ValueError as exc:
            raise SystemExit(f"Invalid ANDROID_VERSION_CODE={version_code_env!r}: expected an integer") from exc
    else:
        version_code = manifest_version_code
    if version_code < manifest_version_code:
        raise SystemExit(
            f"Refusing versionCode {version_code} < AndroidManifest.xml versionCode {manifest_version_code} "
            f"(would cause INSTALL_FAILED_VERSION_DOWNGRADE)"
        )

    signing = resolve_signing_spec(args)

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
        aapt2_link = [
            bt / f"aapt2{ext}", "link", "-I", jar, "--manifest", "AndroidManifest.xml",
            "--auto-add-overlay", "-R", "resources.zip", "-A", "assets",
        ]
        if version_code != manifest_version_code:
            aapt2_link.extend(["--version-code", str(version_code)])
        aapt2_link.extend(["-o", "resources.apk"])
        run(aapt2_link, stage)
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
        if signing["ks_bytes"] is not None:
            key = stage / "signing-keystore.p12"
            key.write_bytes(signing["ks_bytes"])
            try:
                key.chmod(0o600)
            except OSError:
                pass
        elif signing["ks_path"] is not None:
            key = signing["ks_path"]
        else:
            key = local / "debug-signing/emberfall-debug.p12"
            if not key.is_file():
                key.parent.mkdir(parents=True, exist_ok=True)
                run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
                     "-alias", signing["key_alias"], "-storepass", signing["ks_pass"],
                     "-keypass", signing["key_pass"], "-dname", "CN=Emberfall Debug, O=SystemNNN-Emberfall",
                     "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
                     "-validity", "10000", "-noprompt"])
        ks_pass_file = stage / "ks.pass"
        key_pass_file = stage / "key.pass"
        ks_pass_file.write_text(signing["ks_pass"] + "\n", encoding="utf-8")
        key_pass_file.write_text(signing["key_pass"] + "\n", encoding="utf-8")
        for pass_file in (ks_pass_file, key_pass_file):
            try:
                pass_file.chmod(0o600)
            except OSError:
                pass
        print(f"Signing APK (versionCode={version_code}) with {signing['desc']} (alias: {signing['key_alias']})")
        signer = [java, "-jar", bt / "lib/apksigner.jar"]
        run(signer + [
            "sign",
            "--ks", key,
            "--ks-key-alias", signing["key_alias"],
            "--ks-pass", f"file:{ks_pass_file}",
            "--key-pass", f"file:{key_pass_file}",
            "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", "signed.apk",
            "aligned.apk",
        ], stage)
        run(signer + ["verify", "--verbose", "--print-certs", "signed.apk"], stage)
        run([bt / f"zipalign{ext}", "-c", "-P", "16", "4", "signed.apk"], stage)
        final = (args.output or OUTPUT / apk_filename(version, linkage)).resolve()
        final.parent.mkdir(parents=True, exist_ok=True)
        pending = final.with_suffix(".tmp")
        shutil.copyfile(stage / "signed.apk", pending)
        pending.replace(final)
        print(final)

if __name__ == "__main__":
    main()
