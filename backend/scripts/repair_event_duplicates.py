#!/usr/bin/env python3
"""Offline, explicitly reviewed event merges. Default execution is a read-only rehearsal.

No fuzzy matching, automatic approval, value conversion, or INSERT OR REPLACE.
See docs/event-deduplication.md before use. Requires Python 3.11+ (stdlib only).
"""

import argparse
from contextlib import closing
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import re
import sqlite3
import tempfile


EVENT_TABLES = {
    "event_provider_alias",
    "event_observation",
    "market_snapshot",
    "market_reaction",
    "analysis_report",
    "ai_analysis",
    "shared_ai_job",
    "event_market_selection",
    "event_description",
    "data_missing_notice",
    "llm_usage",
}
AUDIT_TABLE = "event_duplicate_merge"


def connect(path, mode="ro"):
    db = sqlite3.connect(Path(path).resolve().as_uri() + f"?mode={mode}", uri=True)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA busy_timeout=10000")
    return db


def rows(db, sql, args=()):
    return [dict(row) for row in db.execute(sql, args)]


def event(db, event_id):
    result = rows(db, "SELECT * FROM economic_event WHERE id=?", (event_id,))
    if not result:
        raise ValueError(f"event {event_id} is missing (already merged or stale plan)")
    return result[0]


def instant(raw):
    # datetime alone truncates nanoseconds; keep the fractional component separately.
    match = re.fullmatch(
        r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d+)?(Z|[+-]\d\d:\d\d)", raw
    )
    if not match:
        raise ValueError(f"unsupported exact timestamp: {raw}")
    base, fraction, zone = match.groups()
    return (
        datetime.fromisoformat(base + zone.replace("Z", "+00:00")).astimezone(
            timezone.utc
        ),
        Decimal(fraction or "0"),
    )


def prepare(db, report):
    groups = []
    for group in report:
        if group["classification"] != "高置信重复":
            continue
        records = []
        for reported in group["records"]:
            live = event(db, reported["id"])
            for field in ("provider", "provider_id", "country", "event", "time_exact"):
                if live[field] != reported[field]:
                    raise ValueError(
                        f"{group['group']}: report identity changed: {field}; re-audit first"
                    )
            if instant(live["event_time"]) != instant(reported["event_time"]):
                raise ValueError(
                    f"{group['group']}: report release time changed; re-audit first"
                )
            records.append(live)
        groups.append(
            {
                "group": group["group"],
                "classification": group["classification"],
                "approved": False,
                "keep_id": None,
                "reason": "",
                "records": records,
            }
        )
    return {"version": 1, "groups": groups}


def reference_tables(db):
    """Fail closed on schema extensions rather than silently cascading unknown data away."""
    found = set()
    audit_exists = False
    for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        quoted = '"' + table.replace('"', '""') + '"'
        columns = {row[1] for row in db.execute(f"PRAGMA table_info({quoted})")}
        foreign_keys = list(db.execute(f"PRAGMA foreign_key_list({quoted})"))
        event_refs = [row for row in foreign_keys if row[2] == "economic_event"]
        if table == AUDIT_TABLE:
            audit_exists = True
            continue
        if table == "economic_event":
            if event_refs:
                raise ValueError("unexpected self-reference in economic_event")
            continue
        if "event_id" in columns or event_refs:
            if table not in EVENT_TABLES or any(
                row[3] != "event_id" for row in event_refs
            ):
                raise ValueError(f"unhandled event reference table: {table}")
            found.add(table)
    if "event_provider_alias" not in found or not audit_exists:
        raise ValueError(
            "upgrade the backend and apply migrations through 031 before repair"
        )
    return sorted(found)


def validate_group(db, group):
    if (
        group.get("classification") != "高置信重复"
        or not group.get("reason", "").strip()
    ):
        raise ValueError(
            "only reviewed high-confidence groups with a written reason may be merged"
        )
    records = group["records"]
    ids = [record["id"] for record in records]
    if len(ids) < 2 or len(set(ids)) != len(ids) or group["keep_id"] not in ids:
        raise ValueError("choose one keep_id from at least two distinct records")
    for snapshot in records:
        if event(db, snapshot["id"]) != snapshot:
            raise ValueError(
                f"event {snapshot['id']} changed since plan creation; prepare and review again"
            )
    keep = next(record for record in records if record["id"] == group["keep_id"])
    periods, phases = set(), set()
    for record in records:
        metadata = json.loads(record.get("release_metadata") or "{}")
        if not isinstance(metadata, dict):
            raise ValueError("invalid release metadata")
        if metadata.get("period"):
            periods.add(metadata["period"].strip())
        words = set(re.findall(r"[a-z]+", record["event"].lower()))
        if words & {"final", "revised"}:
            phases.add("final")
        if words & {"prel", "prelim", "preliminary", "adv", "advance"}:
            phases.add("preliminary")
        if (
            not record["time_exact"]
            or record["country"] != keep["country"]
            or instant(record["event_time"]) != instant(keep["event_time"])
        ):
            raise ValueError("country, exact instant and time_exact must agree")
        if record["provider"] not in {"trading_view", "forex_factory", "biquote"}:
            raise ValueError(
                "authenticated historical imports are not merge candidates"
            )
        if keep["actual"] is None and record["actual"] is not None:
            raise ValueError(
                "keeper is missing Actual; choose a valued keeper after checking its basis"
            )
    if len(periods) > 1:
        raise ValueError(
            "different explicit periods; reconcile upstream metadata and re-audit first"
        )
    if len(phases) > 1:
        raise ValueError("contradictory release phases")
    # Missing periods/phases and different names/units must be explicitly reviewed,
    # never guessed from equal numbers. Even equivalent period formats fail closed here.
    # Keep all primary fields untouched; archive competing values along with their source/unit.
    return keep, [record for record in records if record["id"] != keep["id"]]


def merge_group(db, group, tables):
    keep, removed = validate_group(db, group)
    target = keep["id"]
    ids = {target, *(record["id"] for record in removed)}
    for record in [keep, *removed]:
        owner = db.execute(
            "SELECT event_id FROM event_provider_alias WHERE provider=? AND provider_id=?",
            (record["provider"], record["provider_id"]),
        ).fetchone()
        if owner and owner[0] not in ids:
            raise ValueError("provider identity belongs to an event outside this group")
        # Seed missing aliases BEFORE deleting any identity; never overwrite an external owner.
        if not owner:
            db.execute(
                "INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES(?,?,?)",
                (record["provider"], record["provider_id"], record["id"]),
            )
    for record in removed:
        old_id = record["id"]
        archive = {"event": record, "references": {}}
        for table in tables:
            archive["references"][table] = rows(
                db, f'SELECT * FROM "{table}" WHERE event_id=?', (old_id,)
            )
            # Any unique collision aborts this entire group. In particular, AI analyses/jobs
            # and their feedback are NEVER dropped or arbitrarily picked as the winner.
            db.execute(
                f'UPDATE "{table}" SET event_id=? WHERE event_id=?', (target, old_id)
            )
        db.execute(
            f"UPDATE {AUDIT_TABLE} SET canonical_id=? WHERE canonical_id=?",
            (target, old_id),
        )
        db.execute(
            f"INSERT INTO {AUDIT_TABLE} VALUES(?,?,?,?,?)",
            (
                old_id,
                target,
                datetime.now(timezone.utc).isoformat(),
                group["reason"],
                json.dumps(archive, ensure_ascii=False),
            ),
        )
        db.execute("DELETE FROM economic_event WHERE id=?", (old_id,))
    return {
        "group": group["group"],
        "keep_id": target,
        "removed_ids": [r["id"] for r in removed],
    }


def execute_plan(db, plan):
    if plan.get("version") != 1:
        raise ValueError("unsupported plan version")
    selected = [group for group in plan["groups"] if group.get("approved") is True]
    if not selected:
        raise ValueError("no explicitly approved groups")
    all_ids = [r["id"] for group in selected for r in group["records"]]
    if len(set(all_ids)) != len(all_ids):
        raise ValueError("approved groups overlap")
    tables = reference_tables(db)
    results, blocked = [], []
    for group in selected:
        db.execute("SAVEPOINT duplicate_group")
        try:
            results.append(merge_group(db, group, tables))
        except (ValueError, sqlite3.IntegrityError) as error:
            db.execute("ROLLBACK TO duplicate_group")
            blocked.append({"group": group["group"], "error": str(error)})
        finally:
            db.execute("RELEASE duplicate_group")
    if list(db.execute("PRAGMA foreign_key_check")):
        raise ValueError("foreign key check failed")
    if [row[0] for row in db.execute("PRAGMA integrity_check")] != ["ok"]:
        raise ValueError("integrity check failed")
    return {"merges": results, "blocked": blocked}


def rehearse(path, plan):
    # Market snapshots can dwarf the calendar. Use disk, not an unbounded RAM clone.
    with tempfile.TemporaryDirectory(prefix="event-repair-") as directory:
        with (
            closing(connect(path)) as source,
            closing(sqlite3.connect(Path(directory) / "rehearsal.db")) as copy,
        ):
            source.backup(copy)
            copy.row_factory = sqlite3.Row
            copy.execute("PRAGMA foreign_keys=ON")
            copy.execute("BEGIN IMMEDIATE")
            return execute_plan(copy, plan)


def apply(path, plan, backup_path):
    # Rehearsal is necessary but not sufficient: revalidate under the writer lock too.
    result = rehearse(path, plan)
    if result["blocked"]:
        raise ValueError(f"blocked groups; nothing applied: {result['blocked']}")
    with closing(connect(path, "rw")) as db:
        db.execute("BEGIN IMMEDIATE")
        try:
            # A separate read connection can back up the last committed state while this
            # connection excludes other writers. Never run backup() on the writing handle.
            with open(backup_path, "xb"):
                pass
            with (
                closing(connect(path)) as source,
                closing(sqlite3.connect(backup_path)) as backup,
            ):
                source.backup(backup)
                if backup.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise ValueError("backup integrity check failed")
            result = execute_plan(db, plan)
            if result["blocked"]:
                raise ValueError(
                    f"database changed or conflicts found; rolled back: {result['blocked']}"
                )
            db.commit()
            return result
        except BaseException:
            db.rollback()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument(
        "--prepare-report",
        help="audit JSON; create an UNAPPROVED plan from live snapshots",
    )
    parser.add_argument(
        "--plan",
        required=True,
        help="output for prepare; reviewed input for dry-run/apply",
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--service-stopped", action="store_true")
    parser.add_argument("--backup", help="new backup filename (must not exist)")
    args = parser.parse_args()
    try:
        if args.prepare_report:
            if args.apply:
                raise ValueError("prepare and apply cannot be combined")
            report = json.loads(Path(args.prepare_report).read_text(encoding="utf-8"))
            with closing(connect(args.database)) as db:
                db.execute("BEGIN")
                plan = prepare(db, report)
            with open(args.plan, "x", encoding="utf-8") as output:
                json.dump(plan, output, ensure_ascii=False, indent=2)
            print(
                "Unapproved plan written. Review identities, periods, phases, units, values and references."
            )
            return
        plan = json.loads(Path(args.plan).read_text(encoding="utf-8"))
        if args.apply:
            if not args.service_stopped or not args.backup:
                raise ValueError(
                    "apply requires --service-stopped and --backup NEW_FILE"
                )
            result = apply(args.database, plan, args.backup)
        else:
            result = rehearse(args.database, plan)
        print(
            json.dumps({"applied": args.apply, **result}, ensure_ascii=False, indent=2)
        )
        if result["blocked"]:
            raise SystemExit(2)
    except (ValueError, KeyError, TypeError, OSError, sqlite3.Error) as error:
        parser.exit(2, f"Repair refused: {error}\n")


if __name__ == "__main__":
    main()
