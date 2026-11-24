"""Remove reproducible output only; preserve databases, credentials and dependencies."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
BUILD_DIRS = (
    'apps/web/dist', 'apps/web/dist-driver', 'apps/web/dist-customer',
    'apps/web/node_modules/.vite', 'apps/web/node_modules/.vite-temp',
    'services/logistics/target', 'services/auth/target', 'services/customer/target',
)
SOURCE_DIRS = ('scripts', 'tests', 'services/optimizer')


def clean(root=ROOT, dry_run=False):
    candidates = [root / path for path in BUILD_DIRS]
    for name in SOURCE_DIRS:
        directory = root / name
        if directory.exists():
            candidates.extend(path for path in directory.rglob('__pycache__')
                              if not any(part in {'.venv', 'venv', 'node_modules'} for part in path.parts))
    removed = []
    for path in candidates:
        if path.is_dir() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()):
            removed.append(str(path.relative_to(root)))
            if not dry_run:
                shutil.rmtree(path)
    return removed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    for name in clean(dry_run=args.dry_run):
        print(('Would remove: ' if args.dry_run else 'Removed: ') + name)
