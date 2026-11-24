"""Read-only validation of source imports, Java packages and Compose file paths."""
from pathlib import Path
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def check(root=ROOT):
    errors = []
    for base in ('apps/web/src', 'apps/web/tests'):
        for path in (root / base).rglob('*'):
            if path.suffix not in {'.js', '.jsx'}:
                continue
            for match in re.finditer(r'''(?:\bfrom\s*|\bimport\s*)["'](\.[^"']+)["']''', path.read_text()):
                target = path.parent / match[1]
                if not target.is_file():
                    errors.append(f'{path.relative_to(root)}: missing import {match[1]}')
    for source in (root / 'services').glob('*/src/main/java'):
        for path in source.rglob('*.java'):
            package = re.search(r'^package\s+([^;]+);', path.read_text(), re.M)
            if not package or path.parent.relative_to(source).parts != tuple(package[1].split('.')):
                errors.append(f'{path.relative_to(root)}: package does not match directory')
    result = subprocess.run(['docker', 'compose', 'config', '--format', 'json'], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        errors.append('Compose configuration failed; check local environment settings')
    else:
        config = json.loads(result.stdout)
        for name, service in config['services'].items():
            build = service.get('build')
            if build:
                directory = Path(build['context'])
                if not directory.is_dir() or not (directory / build.get('dockerfile', 'Dockerfile')).is_file():
                    errors.append(f'{name}: missing build context or Dockerfile')
            for volume in service.get('volumes', []):
                if volume.get('type') == 'bind' and not Path(volume['source']).exists():
                    errors.append(f'{name}: missing bind-mounted source')
    return errors


if __name__ == '__main__':
    errors = check()
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        raise SystemExit(1)
    print('PASS: relative imports, Java packages and Compose build/bind paths')
