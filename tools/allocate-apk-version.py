#!/usr/bin/env python3
"""Reserve a fresh APK version across cooperating Git worktrees, before packaging."""
import argparse
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile

MAX_CODE = 2_100_000_000
MAX_PART = 2_147_483_647
MINIMUM = (18, (1, 6, 1))


def read_counter(path):
    lines = [line.strip() for line in path.read_text().splitlines() if line.strip() and not line.startswith('#')]
    pairs = [line.split('=', 1) for line in lines]
    if any(len(pair) != 2 for pair in pairs) or len(pairs) != 2:
        raise ValueError(f'Invalid version counter: {path}')
    values = dict(pairs)
    if set(values) != {'versionCode', 'versionName'}:
        raise ValueError(f'Invalid version counter keys: {path}')
    code = int(values['versionCode'])
    parts = tuple(int(part) for part in values['versionName'].split('.'))
    if len(parts) != 3 or any(part < 0 or part > MAX_PART for part in parts):
        raise ValueError(f'Invalid major.minor.patch version: {path}')
    if code < MINIMUM[0] or parts < MINIMUM[1] or code > MAX_CODE:
        raise ValueError(f'Counter regressed below verified history or exceeds Android limits: {path}')
    return code, parts


def atomic_write(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.apk-version-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as handle:
            handle.write(contents)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def git(repo, *arguments):
    return subprocess.check_output(['git', '-C', str(repo), *arguments], text=True).strip()


def reserve(counter, output):
    repo = counter.parent
    common = Path(git(repo, 'rev-parse', '--path-format=absolute', '--git-common-dir')).resolve(strict=True)
    # Never fall back to a checkout-local lock: that could duplicate an allocation.
    ledger = common / 'bridgit-apk-version.properties'
    with (common / 'bridgit-apk-version.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        observed = [read_counter(counter)]
        if ledger.exists():
            observed.append(read_counter(ledger))
        # Recover the high water from registered checkouts too. A malformed
        # existing counter fails closed; a legacy checkout without one is ignored.
        for line in git(repo, 'worktree', 'list', '--porcelain').splitlines():
            if line.startswith('worktree '):
                sibling = Path(line[len('worktree '):]) / 'apk-version.properties'
                if sibling.exists() and sibling.resolve() != counter.resolve():
                    observed.append(read_counter(sibling))
        code = max(item[0] for item in observed) + 1
        version = max(item[1] for item in observed)
        version = (*version[:2], version[2] + 1)
        if code > MAX_CODE or version[2] > MAX_PART:
            raise ValueError('APK version overflow; explicitly advance the release scheme before building')
        contents = f'versionCode={code}\nversionName={".".join(map(str, version))}\n'
        # Reserve globally first. Failure after this point leaves a deliberate gap.
        atomic_write(ledger, contents)
        atomic_write(counter, contents)
        atomic_write(output, contents)
        return contents


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--counter', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        print(reserve(args.counter.resolve(), args.output.resolve()), end='')
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'APK version allocation refused: {error}\n')
