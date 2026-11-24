from pathlib import Path
import tempfile
import unittest

from scripts.dev.clean import clean


class CleanTest(unittest.TestCase):
    def test_cleanup_preserves_source_credentials_dependencies_and_backups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preserved = ('.env', '.env.local', 'backups/current.dump',
                         'apps/web/src/app/App.jsx', 'apps/web/node_modules/local/index.js',
                         'services/logistics/src/main/resources/db/migration.sql')
            for name in preserved + ('apps/web/dist/customer/index.html', 'services/logistics/target/app.jar',
                                      'scripts/dev/__pycache__/clean.pyc'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            preview = clean(root, dry_run=True)
            self.assertIn('apps/web/dist', preview)
            self.assertTrue((root / 'apps/web/dist/customer/index.html').exists())
            clean(root)
            self.assertFalse((root / 'apps/web/dist').exists())
            self.assertFalse((root / 'services/logistics/target').exists())
            for name in preserved:
                self.assertEqual((root / name).read_text(), 'fixture')

    def test_cleanup_does_not_follow_symlinks_outside_the_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root / 'keep'
            outside.mkdir()
            (outside / 'important').write_text('fixture')
            (root / 'apps/web').mkdir(parents=True)
            (root / 'apps/web/dist').symlink_to(outside, target_is_directory=True)
            clean(root)
            self.assertEqual((outside / 'important').read_text(), 'fixture')

    def test_cleanup_does_not_follow_a_symlinked_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root, outside = base / 'repo', base / 'outside'
            (root / 'apps').mkdir(parents=True)
            (outside / 'dist').mkdir(parents=True)
            (outside / 'dist/keep').write_text('fixture')
            (root / 'apps/web').symlink_to(outside, target_is_directory=True)
            clean(root)
            self.assertEqual((outside / 'dist/keep').read_text(), 'fixture')
