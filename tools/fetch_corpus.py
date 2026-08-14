#!/usr/bin/env python3
"""Fill the local test corpus with every catalogue watch-face package.

The corpus is never committed — these are published packages this project has no
right to redistribute. See "The test corpus" in README.md.

The store's package endpoint needs the stock plugin's signed request parameters, so
rather than reconstruct those, this asks the app to fetch each package through its own
download path. It drives the debug-only CorpusFetchReceiver (absent from release
builds) instead of the download sheet, because a UI dump on a typical emulator costs
about twenty seconds, which makes coordinate-driven automation slow and fragile.

Packages are then copied out of the app cache and their containers extracted into the
layout the corpus tests expect:

    <corpus>/packages/<appId>@<versionCode>.apk
    <corpus>/SM_R390/SM-R390_<id>_256x402/SM-R390_<id>_256x402.bin

Existing files are never overwritten, so archived fixtures keep their exact bytes.

Requires the debug build installed and the app opened once so the catalogue is cached.

Usage:
    python3 tools/fetch_corpus.py <corpus-dir> [face-id ...]
"""
import json
import os
import re
import subprocess
import sys
import time
import zipfile

PACKAGE = "dev.fitface.studio"
CACHE = f"/data/data/{PACKAGE}/files/catalog-cache"
DEVICE_TIMEOUT = 180


def adb(*args, timeout=60, check=True):
    result = subprocess.run(
        ["adb", *args], capture_output=True, text=True, timeout=timeout
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def run_as(command, timeout=60):
    # One argument, quoted here: `adb shell` re-tokenizes whatever it is given, so a
    # command split across argv arrives on the device with its quoting stripped.
    quoted = command.replace("'", "'\\''")
    return adb(
        "shell", f"run-as {PACKAGE} sh -c '{quoted}'", timeout=timeout, check=False
    )


def cached_packages():
    listing = run_as(f"ls {CACHE}/packages 2>/dev/null")
    return {line.strip() for line in listing.splitlines() if line.strip().endswith(".apk")}


def read_catalog():
    text = run_as(f"cat {CACHE}/catalog.json", timeout=120)
    if not text.strip():
        raise SystemExit(
            "No cached catalogue on the device. Open FitFace Studio once so it syncs."
        )
    return json.loads(text)["faces"]


RECEIVER = f"{PACKAGE}/dev.fitface.studio.debug.CorpusFetchReceiver"
ACTION = "dev.fitface.studio.debug.FETCH"


def fetch_via_app(face_id, before, timeout=180):
    """Asks the app to download one package. Returns the new cache filename."""
    adb("logcat", "-c", check=False)
    adb(
        "shell", "am", "broadcast", "-a", ACTION, "-n", RECEIVER,
        "--es", "faceId", face_id, timeout=60, check=False,
    )
    deadline = time.time() + timeout
    while time.time() < deadline:
        new = cached_packages() - before
        if new:
            return sorted(new)[0]
        log = adb("logcat", "-d", "-s", "CorpusFetch:*", timeout=60, check=False)
        for line in log.splitlines():
            if "FETCH_RESULT" not in line or face_id not in line:
                continue
            if " ok " in line:
                # Cache listing can lag the log line by a moment.
                for _ in range(5):
                    new = cached_packages() - before
                    if new:
                        return sorted(new)[0]
                    time.sleep(1)
            else:
                raise RuntimeError(line.split("FETCH_RESULT", 1)[1].strip())
        time.sleep(2)
    raise RuntimeError("timed out waiting for the app to download it")


def pull_package(name, corpus):
    target = os.path.join(corpus, "packages", name)
    if os.path.exists(target):
        return target
    os.makedirs(os.path.dirname(target), exist_ok=True)
    # run-as cannot write to /sdcard on all builds; cat through the shell instead.
    with open(target, "wb") as output:
        process = subprocess.run(
            ["adb", "exec-out", "run-as", PACKAGE, "cat", f"{CACHE}/packages/{name}"],
            stdout=output, stderr=subprocess.PIPE, timeout=300,
        )
    if process.returncode != 0 or os.path.getsize(target) == 0:
        os.remove(target)
        raise RuntimeError(f"could not pull {name}: {process.stderr.decode()[:200]}")
    return target


def extract_container(apk, face_id, corpus):
    member = f"assets/SM-R390_{face_id}_256x402.bin"
    with zipfile.ZipFile(apk) as archive:
        if member not in archive.namelist():
            return None
        directory = os.path.join(corpus, "SM_R390", f"SM-R390_{face_id}_256x402")
        os.makedirs(directory, exist_ok=True)
        target = os.path.join(directory, f"SM-R390_{face_id}_256x402.bin")
        if not os.path.exists(target):
            with archive.open(member) as source, open(target, "wb") as output:
                output.write(source.read())
        return target


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    corpus = sys.argv[1]
    only = set(sys.argv[2:])
    faces = sorted(read_catalog(), key=lambda f: f["faceId"])
    if only:
        faces = [f for f in faces if f["faceId"] in only]

    report = {"extracted": [], "no_container": [], "failed": {}}
    for index, face in enumerate(faces, start=1):
        face_id = face["faceId"]
        label = f"[{index}/{len(faces)}] {face_id} {face['name']}"
        expected = f"{face['appId']}@{face['versionCode']}.apk"
        try:
            before = cached_packages()
            name = expected if expected in before else fetch_via_app(face_id, before)
            apk = pull_package(name, corpus)
            if extract_container(apk, face_id, corpus):
                report["extracted"].append(face_id)
                print(f"{label}: ok", flush=True)
            else:
                report["no_container"].append(face_id)
                print(f"{label}: package holds no container", flush=True)
        except Exception as error:  # noqa: BLE001 - record and continue
            report["failed"][face_id] = str(error)
            print(f"{label}: FAILED {error}", flush=True)
        with open(os.path.join(corpus, "fetch-report.json"), "w") as output:
            json.dump(report, output, indent=1, sort_keys=True)

    print(f"\nextracted={len(report['extracted'])} "
          f"no_container={len(report['no_container'])} failed={len(report['failed'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
