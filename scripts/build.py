#!/usr/bin/env python3
"""Build a small platform-only Android app with the installed SDK; no Gradle downloads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def version(path):
    import re
    return tuple(int(n) for n in re.findall(r"\d+", path.name))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ui-only", action="store_true", help="Build without the Linux payload for UI development")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home()/"Library/Android/sdk"))))
    jdk_default = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    jdk = Path(os.environ.get("JAVA_HOME", str(jdk_default)))
    if not (jdk/"bin/javac").exists():
        raise SystemExit("Set JAVA_HOME to a JDK (Android Studio's bundled JBR is supported).")
    build_tools = max((sdk/"build-tools").iterdir(), key=version)
    platform = max((sdk/"platforms").iterdir(), key=version)
    android = platform/"android.jar"
    source = ROOT/"app/src/main"
    if not args.ui_only and not any((source/"assets/runtime").glob("*.tar.gz")):
        raise SystemExit("Prepare the runtime payload first (see README), or use --ui-only.")
    out = ROOT/"build"
    if out.exists():
        shutil.rmtree(out)
    (out/"classes").mkdir(parents=True)
    (out/"generated").mkdir()
    (out/"dex").mkdir()
    env_path = str(jdk/"bin") + os.pathsep + os.environ.get("PATH", "")
    os.environ["JAVA_HOME"] = str(jdk)
    os.environ["PATH"] = env_path
    run(build_tools/"aapt2", "compile", "--dir", source/"res", "-o", out/"resources.zip")
    run(build_tools/"aapt2", "link", "-o", out/"unsigned.apk", "-I", android,
        "--manifest", source/"AndroidManifest.xml", "--java", out/"generated",
        "--min-sdk-version", "26", "--target-sdk-version", "28", "--debug-mode",
        out/"resources.zip")
    java_files = sorted((source/"java").rglob("*.java")) + sorted((out/"generated").rglob("*.java"))
    run(jdk/"bin/javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-classpath", android, "-d", out/"classes", *java_files)
    run(build_tools/"d8", "--lib", android, "--min-api", "26", "--output", out/"dex", *sorted((out/"classes").rglob("*.class")))
    with zipfile.ZipFile(out/"unsigned.apk", "a") as apk:
        for path in sorted((source/"assets").rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                compression = zipfile.ZIP_STORED if path.suffix == ".gz" else zipfile.ZIP_DEFLATED
                apk.write(path, "assets/" + str(path.relative_to(source/"assets")), compress_type=compression)
        for path in sorted((out/"dex").glob("*.dex")):
            apk.write(path, path.name, compress_type=zipfile.ZIP_DEFLATED)
        libs = source/"jniLibs"
        if libs.exists():
            for path in sorted(libs.rglob("*")):
                if path.is_file():
                    apk.write(path, "lib/" + str(path.relative_to(libs)), compress_type=zipfile.ZIP_STORED)
    run(build_tools/"zipalign", "-f", "-P", "16", "4", out/"unsigned.apk", out/"aligned.apk")
    key = ROOT/"local/debug.keystore"
    if not key.exists():
        key.parent.mkdir(exist_ok=True)
        run(jdk/"bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "phoneagent", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=PhoneAgent Development")
    run(build_tools/"apksigner", "sign", "--ks", key, "--ks-key-alias", "phoneagent", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", out/"PhoneAgent-debug.apk", out/"aligned.apk")
    run(build_tools/"apksigner", "verify", out/"PhoneAgent-debug.apk")
    print(out/"PhoneAgent-debug.apk")


if __name__ == "__main__":
    main()
