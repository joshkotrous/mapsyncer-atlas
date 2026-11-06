"""Exercise optional-client deployment and recovery using real filesystem operations."""
from pathlib import Path
import argparse
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_session', ROOT / 'scripts/testing/client-regression.py')
session = importlib.util.module_from_spec(spec)
spec.loader.exec_module(session)


class ClientSessionRegression(unittest.TestCase):
    def test_relaunch_does_not_lose_running_client(self):
        manifest = {'processes': {'client': 123}}
        with patch.object(session, 'process_running', return_value=True):
            with self.assertRaisesRegex(RuntimeError, 'still running'):
                session.start_client(manifest, ROOT / 'cache/tmp', None)
        self.assertEqual(manifest['processes'], {'client': 123})

    def test_server_only_deployment_restores_both_sides(self):
        cache = ROOT / 'cache/tmp/client-session-tests'
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as folder:
            root = Path(folder)
            session.ROOT = root
            session.CLIENT_ROOT = root / 'clients'
            session.SERVER_ROOT = root / 'servers'
            session.local_port_free = lambda port: True
            client = session.CLIENT_ROOT / 'client'
            server = session.SERVER_ROOT / 'server'
            jar = root / 'output/mapsyncer-new.jar'
            jar.parent.mkdir()
            jar.write_bytes(b'new release')
            for directory in [client, server]:
                (directory / 'mods').mkdir(parents=True)
                (directory / 'config').mkdir()
                (directory / 'mods/mapsyncer-old.jar').write_bytes(b'old release')
                (directory / 'mods/unrelated.jar').write_bytes(b'unrelated')
                (directory / 'config/mapsyncer-synced.toml').write_text('original')
            props = server / 'server.properties'
            props.write_text('enable-rcon=false\nonline-mode=false\n')
            original_props = props.read_bytes()
            session.begin(argparse.Namespace(client=str(client), server=str(server), jar=str(jar),
                                             without_client_mod=True))
            manifest_path = next(root.glob('cache/tmp/client-regression/*/session.json'))
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            self.assertFalse(list((client / 'mods').glob('mapsyncer*.jar')))
            self.assertEqual((server / 'mods' / jar.name).read_bytes(), b'new release')
            self.assertEqual(session.sha(server / 'mods' / jar.name), manifest['sha256'])
            self.assertFalse(manifest['client_mod'])
            for directory in [client, server]:
                (directory / 'config/mapsyncer-synced.toml').write_text('changed')
            session.restore(manifest, manifest_path.parent)
            for directory in [client, server]:
                self.assertEqual((directory / 'mods/mapsyncer-old.jar').read_bytes(), b'old release')
                self.assertEqual((directory / 'mods/unrelated.jar').read_bytes(), b'unrelated')
                self.assertEqual((directory / 'config/mapsyncer-synced.toml').read_text(), 'original')
                self.assertFalse((directory / 'mods' / jar.name).exists())
            self.assertEqual(props.read_bytes(), original_props)
            self.assertFalse((server / 'ops.json').exists())


if __name__ == '__main__':
    unittest.main()
