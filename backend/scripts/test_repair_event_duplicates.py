import copy
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

import repair_event_duplicates as repair


class RepairTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "market.db"
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        for migration in sorted(
            (Path(__file__).parents[1] / "migrations").glob("*.sql")
        ):
            self.db.executescript(migration.read_text(encoding="utf-8"))
        for event_id, provider, actual in [
            (1, "trading_view", "6.74564"),
            (2, "biquote", "6.746"),
            (3, "trading_view", None),
        ]:
            self.db.execute(
                """INSERT INTO economic_event(id,provider,provider_id,country,category,event,event_time,
                importance,actual,unit,status,time_exact) VALUES(?,?,?,'United States','test','Fed Balance Sheet',
                '2026-10-30T04:30:00+08:00',2,?,'T USD','completed',1)""",
                (event_id, provider, str(event_id), actual),
            )
            self.db.execute(
                "INSERT INTO event_provider_alias VALUES(?,?,?)",
                (provider, str(event_id), event_id),
            )
        self.db.commit()
        self.plan = {
            "version": 1,
            "groups": [
                {
                    "group": "test",
                    "classification": "高置信重复",
                    "approved": True,
                    "keep_id": 1,
                    "reason": "Reviewed release, period, phase, units and precision; keep TV basis",
                    "records": [repair.event(self.db, 1), repair.event(self.db, 2)],
                }
            ],
        }
        self.addCleanup(self.db.close)

    def apply(self):
        return repair.apply(
            self.path, self.plan, str(Path(self.temp.name) / "backup.db")
        )

    def test_dry_run_is_read_only_then_apply_preserves_keeper_aliases_history_and_backup(
        self,
    ):
        self.db.execute(
            "INSERT INTO event_observation(event_id,observed_at,actual) VALUES(2,'now','6.746')"
        )
        self.db.execute(
            "INSERT INTO llm_usage(event_id,model,scope,kind,endpoint_host,status,total_tokens,created_at) VALUES(2,'test','test','test','test','ok',2,'now')"
        )
        self.db.execute("INSERT INTO data_missing_notice VALUES(2,'now')")
        self.db.commit()
        before = self.path.read_bytes()
        self.assertFalse(repair.rehearse(self.path, self.plan)["blocked"])
        self.assertEqual(before, self.path.read_bytes())
        self.assertFalse(self.apply()["blocked"])
        self.assertEqual(repair.event(self.db, 1), self.plan["groups"][0]["records"][0])
        self.assertEqual(
            self.db.execute(
                "SELECT event_id FROM event_provider_alias WHERE provider='biquote'"
            ).fetchone()[0],
            1,
        )
        for table in ("event_observation", "llm_usage", "data_missing_notice"):
            self.assertEqual(
                self.db.execute(f"SELECT event_id FROM {table}").fetchone()[0], 1
            )
        archive = json.loads(
            self.db.execute(
                "SELECT archive_json FROM event_duplicate_merge WHERE old_id=2"
            ).fetchone()[0]
        )
        self.assertEqual(archive["event"]["actual"], "6.746")
        self.assertEqual(archive["references"]["event_observation"][0]["event_id"], 2)
        with closing(sqlite3.connect(Path(self.temp.name) / "backup.db")) as backup:
            self.assertEqual(
                backup.execute("SELECT count(*) FROM economic_event").fetchone()[0], 3
            )
        with self.assertRaises(ValueError):
            self.apply()

    def test_unique_conflicts_block_without_losing_either_description(self):
        for event_id in (1, 2):
            self.db.execute(
                "INSERT INTO event_description VALUES(?,?,?,?,?,?)",
                (event_id, str(event_id), "cn", "tw", "now", "now"),
            )
        self.db.commit()
        self.assertEqual(len(repair.rehearse(self.path, self.plan)["blocked"]), 1)
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(
            self.db.execute("SELECT count(*) FROM event_description").fetchone()[0], 2
        )
        self.assertEqual(
            self.db.execute("SELECT count(*) FROM economic_event").fetchone()[0], 3
        )

    def test_ai_feedback_and_notifications_follow_unchanged_analysis_id(self):
        self.db.execute("""INSERT INTO ai_analysis(id,event_id,language,method,chain_json,data_analysis,market_outlook,model,generated_at)
            VALUES(50,2,'en',1,'{}','analysis','outlook','model','now')""")
        self.db.execute(
            "INSERT INTO analysis_feedback(id,analysis_id,revision,requested_by,message,created_at) VALUES(60,50,1,'user','feedback','now')"
        )
        self.db.execute(
            "INSERT INTO analysis_feedback_notification VALUES(60,123,'now')"
        )
        self.db.execute(
            "INSERT INTO shared_ai_job(event_id,method,language) VALUES(2,1,'en')"
        )
        self.db.commit()
        self.apply()
        self.assertEqual(
            tuple(self.db.execute("SELECT id,event_id FROM ai_analysis").fetchone()),
            (50, 1),
        )
        self.assertEqual(
            self.db.execute("SELECT analysis_id FROM analysis_feedback").fetchone()[0],
            50,
        )
        self.assertEqual(
            self.db.execute(
                "SELECT feedback_id FROM analysis_feedback_notification"
            ).fetchone()[0],
            60,
        )
        self.assertEqual(
            self.db.execute("SELECT event_id FROM shared_ai_job").fetchone()[0], 1
        )

    def test_backup_includes_committed_wal_and_old_mappings_are_flattened(self):
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute(
            "INSERT INTO event_duplicate_merge VALUES(99,2,'now','earlier repair','{}')"
        )
        self.db.commit()
        self.apply()
        self.assertEqual(
            self.db.execute(
                "SELECT canonical_id FROM event_duplicate_merge WHERE old_id=99"
            ).fetchone()[0],
            1,
        )
        with closing(sqlite3.connect(Path(self.temp.name) / "backup.db")) as backup:
            self.assertEqual(
                backup.execute(
                    "SELECT canonical_id FROM event_duplicate_merge WHERE old_id=99"
                ).fetchone()[0],
                2,
            )
            self.assertEqual(
                backup.execute("PRAGMA integrity_check").fetchone()[0], "ok"
            )

    def test_existing_backup_is_never_overwritten(self):
        backup = Path(self.temp.name) / "backup.db"
        backup.write_bytes(b"existing backup")
        with self.assertRaises(FileExistsError):
            self.apply()
        self.assertEqual(backup.read_bytes(), b"existing backup")
        self.assertEqual(
            self.db.execute("SELECT count(*) FROM economic_event").fetchone()[0], 3
        )

    def test_stale_snapshots_and_overlapping_groups_are_rejected(self):
        self.db.execute("UPDATE economic_event SET actual='7' WHERE id=2")
        self.db.commit()
        self.assertIn(
            "changed", repair.rehearse(self.path, self.plan)["blocked"][0]["error"]
        )
        with self.assertRaises(ValueError):
            self.apply()
        overlap = copy.deepcopy(self.plan)
        overlap["groups"].append(copy.deepcopy(overlap["groups"][0]))
        with self.assertRaisesRegex(ValueError, "overlap"):
            repair.rehearse(self.path, overlap)

    def test_unknown_references_and_alias_ownership_are_rejected(self):
        self.db.execute(
            "UPDATE event_provider_alias SET event_id=3 WHERE provider='biquote'"
        )
        self.db.commit()
        self.assertIn(
            "outside", repair.rehearse(self.path, self.plan)["blocked"][0]["error"]
        )
        self.db.execute(
            "CREATE TABLE future_relation(owner INTEGER REFERENCES economic_event(id))"
        )
        with self.assertRaisesRegex(ValueError, "unhandled"):
            repair.rehearse(self.path, self.plan)

    def test_prepare_does_not_approve_report_and_excludes_review_groups(self):
        records = self.plan["groups"][0]["records"]
        report = [
            {"group": "safe", "classification": "高置信重复", "records": records},
            {"group": "review", "classification": "待复核", "records": records},
        ]
        result = repair.prepare(self.db, report)
        self.assertEqual(len(result["groups"]), 1)
        self.assertFalse(result["groups"][0]["approved"])
        self.assertIsNone(result["groups"][0]["keep_id"])
        with self.assertRaisesRegex(ValueError, "no explicitly approved"):
            repair.rehearse(self.path, result)

    def test_nanosecond_instants_remain_distinct_and_equivalent_offsets_match(self):
        self.assertEqual(
            repair.instant("2026-11-04T13:30:00Z"),
            repair.instant("2026-11-04T21:30:00+08:00"),
        )
        self.assertNotEqual(
            repair.instant("2026-11-04T13:30:00.000000001Z"),
            repair.instant("2026-11-04T13:30:00Z"),
        )

    def test_atomic_refusal_when_one_approved_group_is_blocked(self):
        for event_id in (4, 5):
            self.db.execute(
                """INSERT INTO economic_event(id,provider,provider_id,country,category,event,
                event_time,importance,time_exact) SELECT ?,provider,?,country,category,event,event_time,
                importance,time_exact FROM economic_event WHERE id=3""",
                (event_id, str(event_id)),
            )
        self.db.commit()
        other = copy.deepcopy(self.plan["groups"][0])
        other.update(
            group="blocked",
            keep_id=4,
            records=[repair.event(self.db, 4), repair.event(self.db, 5)],
        )
        other["records"][1]["country"] = "Canada"
        self.plan["groups"].append(other)
        rehearsal = repair.rehearse(self.path, self.plan)
        self.assertEqual(len(rehearsal["merges"]), 1)
        self.assertEqual(len(rehearsal["blocked"]), 1)
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(
            self.db.execute("SELECT count(*) FROM economic_event").fetchone()[0], 5
        )
        self.assertFalse((Path(self.temp.name) / "backup.db").exists())

    def test_explicit_period_and_phase_conflicts_fail_even_with_manual_approval(self):
        for changes in [
            {
                1: ("release_metadata", '{"period":"2026-09"}'),
                2: ("release_metadata", '{"period":"2026-10"}'),
            },
            {1: ("event", "PMI Final"), 2: ("event", "PMI Prel")},
        ]:
            for event_id, (field, value) in changes.items():
                self.db.execute(
                    f"UPDATE economic_event SET {field}=? WHERE id=?", (value, event_id)
                )
            self.db.commit()
            self.plan["groups"][0]["records"] = [
                repair.event(self.db, 1),
                repair.event(self.db, 2),
            ]
            self.assertTrue(repair.rehearse(self.path, self.plan)["blocked"])
            self.db.execute("UPDATE economic_event SET release_metadata=NULL")
            self.db.commit()


if __name__ == "__main__":
    unittest.main()
