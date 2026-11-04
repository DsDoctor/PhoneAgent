#!/usr/bin/env python3
"""Prepare the private ARM64 Linux payload from pinned upstream packages.

No Buddy code or assets are used. Requires Python 3.10+, curl; Python 3.14
can use Claude's smaller Zstandard download. Raw fallback works on older Python.
Generated binaries remain local and must not be committed to Git.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import io
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "local/runtime-downloads"
LOCK = json.loads((ROOT / "scripts/runtime-lock.json").read_text())
PARSER = argparse.ArgumentParser(description=__doc__)
PARSER.add_argument("--proxy", help="Optional HTTPS download proxy, e.g. http://127.0.0.1:7897")
ARGS = PARSER.parse_args()
CACHE.mkdir(parents=True, exist_ok=True)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download(item):
    target = CACHE / item["filename"]
    if target.exists() and sha256(target) == item["sha256"]:
        return target
    temporary = target.with_name(target.name + ".part")
    command = ["curl", "--fail", "--silent", "--show-error", "--location",
               "--retry", "2", "--connect-timeout", "20", "--max-time", "900"]
    if ARGS.proxy:
        command.extend(["--proxy", ARGS.proxy])
    print("Downloading", item["filename"], flush=True)
    subprocess.run(command + [item["url"], "-o", str(temporary)], check=True)
    if sha256(temporary) != item["sha256"]:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("SHA256 mismatch: " + item["filename"])
    temporary.replace(target)
    return target


def tar_members(path, apk=False):
    # APK v2 contains multiple gzip/tar streams; ignore their end-of-archive blocks.
    with tarfile.open(path, "r:*", ignore_zeros=apk) as archive:
        for info in archive:
            name = str(PurePosixPath(info.name)).removeprefix("./")
            if name in ("", "."):
                continue
            if name.startswith("/") or ".." in PurePosixPath(name).parts:
                raise RuntimeError("Unsafe archive path: " + name)
            if apk and name.startswith("."):
                continue  # package signatures/metadata are not part of rootfs
            if info.isdev():
                continue  # /dev is a bind mount on the actual phone
            yield name, info, archive.extractfile(info) if info.isfile() else None


def deb_files(path):
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise RuntimeError("Invalid Debian archive")
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58])
        body = data[offset + 60:offset + 60 + size]
        offset += 60 + size + size % 2
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body), mode="r:*") as archive:
                for info in archive:
                    if info.isfile():
                        yield info.name, archive.extractfile(info).read()


def claude_binary():
    item = LOCK["claude"]
    target = CACHE / item["filename"]
    if target.exists() and sha256(target) == item["sha256"]:
        return target
    try:
        from compression import zstd  # Python 3.14 standard library
    except ImportError:
        return download(item)
    compressed = download(item["compressed"])
    temporary = target.with_name(target.name + ".part")
    with zstd.open(compressed, "rb") as source, temporary.open("wb") as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    if sha256(temporary) != item["sha256"]:
        temporary.unlink(missing_ok=True)
        raise RuntimeError("Decompressed Claude checksum mismatch")
    temporary.replace(target)
    return target


def add_text(archive, path, content, mode=0o644):
    data = content.encode()
    info = tarfile.TarInfo(path)
    info.mode = mode
    info.uid = 1000
    info.gid = 1000
    info.size = len(data)
    archive.addfile(info, io.BytesIO(data))


def build_rootfs(rootfs_archive, packages, claude):
    destination = ROOT / "app/src/main/assets/runtime"
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / "rootfs.tar.gz"
    final_links = {}
    # Preserve links as tar metadata without ever extracting foreign absolute links on
    # the build host. Later packages override rootfs files during device extraction.
    with output.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=6) as gz:
        with tarfile.open(fileobj=gz, mode="w|", format=tarfile.GNU_FORMAT) as archive:
            for path, is_apk in [(rootfs_archive, False)] + [(p, True) for p in packages]:
                for name, info, stream in tar_members(path, apk=is_apk):
                    info.name = name
                    if info.issym():
                        # Android's toybox tar resolves an absolute /etc link against
                        # Android /system/etc, not the future PRoot guest. Make every
                        # guest link relative while preserving its guest destination.
                        if info.linkname.startswith("/"):
                            info.linkname = posixpath.relpath(
                                info.linkname.lstrip("/"), posixpath.dirname(name) or ".")
                        target = posixpath.normpath(posixpath.join(posixpath.dirname(name), info.linkname))
                        if target == ".." or target.startswith("../"):
                            raise RuntimeError("Symlink escapes rootfs: " + name)
                        final_links[name] = info.linkname
                    else:
                        final_links.pop(name, None)
                    if info.islnk():
                        # Hard-link targets are archive-root-relative, unlike symlinks.
                        info.linkname = posixpath.normpath(info.linkname.lstrip("/"))
                        if info.linkname == ".." or info.linkname.startswith("../"):
                            raise RuntimeError("Hard link escapes rootfs: " + name)
                    info.uid = 1000
                    info.gid = 1000
                    info.uname = "agent"
                    info.gname = "agent"
                    info.mtime = 0
                    archive.addfile(info, stream)
            for directory in ("home/agent", "opt/phoneagent", "usr/local/bin"):
                info = tarfile.TarInfo(directory)
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                info.uid = info.gid = 1000
                archive.addfile(info)
            info = tarfile.TarInfo("usr/local/bin/claude")
            info.size = claude.stat().st_size
            info.mode = 0o755
            info.uid = info.gid = 1000
            with claude.open("rb") as stream:
                archive.addfile(info, stream)
            add_text(archive, "etc/passwd", "root:x:0:0:root:/root:/bin/sh\nagent:x:1000:1000:PhoneAgent:/home/agent:/bin/sh\n")
            add_text(archive, "etc/group", "root:x:0:\nagent:x:1000:\n")
            add_text(archive, "etc/resolv.conf", "nameserver 223.5.5.5\nnameserver 1.1.1.1\n")
            add_text(archive, "etc/phoneagent-runtime.json", json.dumps({
                "alpine": LOCK["alpine"]["version"], "claude": LOCK["claude"]["version"],
                "architecture": LOCK["architecture"], "built_from": "scripts/runtime-lock.json"
            }, indent=2) + "\n")
    # Also check chains and symlinked ancestors using guest paths, without touching
    # host paths. Dangling /proc and /dev entries are supplied by runtime bind mounts.
    for name in final_links:
        pending = name
        for hop in range(41):
            parts = pending.split("/")
            for index in range(1, len(parts) + 1):
                prefix = "/".join(parts[:index])
                if prefix not in final_links:
                    continue
                pending = posixpath.normpath(posixpath.join(
                    posixpath.dirname(prefix), final_links[prefix], *parts[index:]))
                if pending == ".." or pending.startswith("../") or pending.startswith("/"):
                    raise RuntimeError("Symlink chain escapes rootfs: " + name)
                break
            else:
                break
        else:
            raise RuntimeError("Symlink cycle in rootfs: " + name)
    print("Validated", len(final_links), "relative rootfs symlinks", flush=True)
    return output


def build_native(debs):
    destination = ROOT / "app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    suffixes = {
        "/usr/bin/proot": "libproot.so",
        "/usr/libexec/proot/loader": "libproot-loader.so",
        "/usr/libexec/proot/loader32": "libproot-loader32.so",
        "/usr/lib/libtalloc.so.2.5.0": "libtalloc.so",
        "/usr/lib/libandroid-shmem.so": "libandroid-shmem.so",
    }
    written = []
    for path in debs:
        for name, data in deb_files(path):
            for suffix, output_name in suffixes.items():
                if name.endswith(suffix):
                    if output_name in ("libproot.so", "libtalloc.so"):
                        # Android extracts native files named lib*.so only. Shortening
                        # DT_NEEDED/SONAME in-place preserves ELF offsets and code.
                        data = data.replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
                    output = destination / output_name
                    output.write_bytes(data)
                    output.chmod(0o755)
                    written.append(output)
    if len(written) != len(suffixes):
        raise RuntimeError("Missing native runtime files")
    return written


def main():
    items = [LOCK["alpine"]] + LOCK["termux"] + LOCK["alpine_packages"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(download, items))
    claude = claude_binary()
    native = build_native(paths[1:1 + len(LOCK["termux"])] )
    rootfs = build_rootfs(paths[0], paths[1 + len(LOCK["termux"]):], claude)
    result = {str(path.relative_to(ROOT)): {"sha256": sha256(path), "bytes": path.stat().st_size}
              for path in [rootfs] + native}
    (ROOT / "local/runtime-payload.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
