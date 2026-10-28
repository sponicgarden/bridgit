#!/usr/bin/env python3
"""Reject mismatched or dirty APK sources before any publication."""
import json
import re
import sys
import zipfile

def check(apk, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full Git commit")
    with zipfile.ZipFile(apk) as archive:
        metadata = json.loads(archive.read("assets/build-provenance.json"))
    if metadata.get("dirty_build") is not False or metadata.get("git_commit") != commit:
        raise ValueError("APK must be a clean build of the current commit")

if __name__ == "__main__":
    check(*sys.argv[1:])
