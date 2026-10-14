"""离线验证新增画像 schema 不覆盖凭据、不放宽历史备份完整性检查。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("infra_manage", Path(__file__).with_name("manage.py"))
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class ProfileSetupTest(unittest.TestCase):
    def test_credentials_only_append_and_are_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = manage.ENV
            manage.ENV = Path(directory) / "credentials.env"
            initial = "# preserved comment\nLC4J_AUTH_APP_PASSWORD=existing-test-value"
            manage.ENV.write_text(initial)
            try:
                first = manage.credentials()
                content = manage.ENV.read_text()
                self.assertTrue(content.startswith(initial + "\n"))
                self.assertEqual(first["LC4J_AUTH_APP_PASSWORD"], "existing-test-value")
                self.assertIn("LC4J_CONVERSATION_APP_PASSWORD", first)
                self.assertIn("LC4J_CONVERSATION_MIGRATOR_PASSWORD", first)
                self.assertEqual(manage.credentials(), first)
                self.assertEqual(manage.ENV.read_text(), content)
                self.assertEqual(manage.ENV.stat().st_mode & 0o777, 0o600)
            finally:
                manage.ENV = previous

    def test_legacy_and_new_backup_schema_sets_are_accepted(self):
        legacy = {key: {} for key in manage.DATABASES if key != "conversation"}
        self.assertEqual(set(manage.migration_databases({"databases": legacy})), set(legacy))
        current = {**legacy, "conversation": {}}
        self.assertEqual(set(manage.migration_databases({"databases": current})), set(current))

    def test_missing_legacy_or_unknown_database_is_rejected(self):
        required = {key: {} for key in manage.DATABASES if key != "conversation"}
        with self.assertRaises(RuntimeError):
            manage.migration_databases({"databases": {key: value for key, value in required.items() if key != "auth"}})
        with self.assertRaises(RuntimeError):
            manage.migration_databases({"databases": {**required, "unexpected": {}}})


if __name__ == "__main__":
    unittest.main()
