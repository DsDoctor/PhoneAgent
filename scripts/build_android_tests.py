#!/usr/bin/env python3
"""Build the separate framework Instrumentation APK after scripts/build.py."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def version(path):
    return tuple(int(n) for n in re.findall(r"\d+", path.name))


def main():
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home()/"Library/Android/sdk"))))
    jdk = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
    if not (jdk/"bin/javac").exists():
        raise SystemExit("Set JAVA_HOME to a JDK.")
    build_tools = max((sdk/"build-tools").iterdir(), key=version)
    android = max((sdk/"platforms").iterdir(), key=version)/"android.jar"
    app_classes = ROOT/"build/classes"
    key = ROOT/"local/debug.keystore"
    if not (app_classes/"com/ds/phoneagent/LinuxRuntime.class").exists() or not key.exists():
        raise SystemExit("Build the app first with scripts/build.py.")
    out = ROOT/"build/android-tests"
    if out.exists():
        shutil.rmtree(out)
    (out/"classes").mkdir(parents=True)
    (out/"dex").mkdir()
    os.environ["JAVA_HOME"] = str(jdk)
    os.environ["PATH"] = str(jdk/"bin") + os.pathsep + os.environ.get("PATH", "")
    run(build_tools/"aapt2", "link", "-o", out/"unsigned.apk", "-I", android,
        "--manifest", ROOT/"tests/android/AndroidManifest.xml", "--min-sdk-version", "26",
        "--target-sdk-version", "28", "--debug-mode")
    source = sorted((ROOT/"tests/android/java").rglob("*.java"))
    run(jdk/"bin/javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-classpath", str(android) + os.pathsep + str(app_classes), "-d", out/"classes", *source)
    with zipfile.ZipFile(out/"app-classes.jar", "w", compression=zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(app_classes.rglob("*.class")):
            jar.write(path, str(path.relative_to(app_classes)))
    run(build_tools/"d8", "--lib", android, "--classpath", out/"app-classes.jar", "--min-api", "26",
        "--output", out/"dex", *sorted((out/"classes").rglob("*.class")))
    with zipfile.ZipFile(out/"unsigned.apk", "a") as apk:
        for path in sorted((out/"dex").glob("*.dex")):
            apk.write(path, path.name, compress_type=zipfile.ZIP_DEFLATED)
    run(build_tools/"zipalign", "-f", "4", out/"unsigned.apk", out/"aligned.apk")
    run(build_tools/"apksigner", "sign", "--ks", key, "--ks-key-alias", "phoneagent", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", out/"PhoneAgent-tests.apk", out/"aligned.apk")
    run(build_tools/"apksigner", "verify", out/"PhoneAgent-tests.apk")
    print(out/"PhoneAgent-tests.apk")


if __name__ == "__main__":
    main()
