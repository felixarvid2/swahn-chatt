"""Server administration: python -m app.cli <command> (run from backend/, or inside the container)."""

import argparse
import os
import secrets
import sys
import time
from pathlib import Path

from sqlalchemy import select

from app.config import ConfigError, Settings
from app.services.backup import BackupError


def _settings() -> Settings:
    # The CLI never signs tokens, so a missing secret must not stop it (production would refuse to start).
    if len(os.environ.get("FAMILYCHAT_SECRET_KEY", "").encode()) < 32:
        return Settings(secret_key=secrets.token_urlsafe(48))
    return Settings()


def _open(settings: Settings):
    from app.db import make_engine, make_session_factory
    from app.migrate import upgrade

    settings.data_dir.mkdir(parents=True, exist_ok=True)
    upgrade(settings)  # no-op when current; lets the CLI run before the first server start
    return make_session_factory(make_engine(settings.database_url))


def cmd_create_invite(args, settings: Settings) -> int:
    from app.routers.admin import create_invitation

    factory = _open(settings)
    with factory() as db:
        inv, code = create_invitation(db, None, args.note, args.hours, args.admin)
        db.commit()
        expires = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(inv.expires_at / 1000))
    print(f"invitation valid until {expires}" + (" (administrator)" if args.admin else ""), file=sys.stderr)
    print(code)  # scripts read the LAST stdout line
    return 0


def cmd_list_users(args, settings: Settings) -> int:
    from app.models import Device, User

    factory = _open(settings)
    with factory() as db:
        users = db.scalars(select(User).order_by(User.created_at)).all()
        print(f"{'username':<20} {'display name':<24} {'role':<7} {'devices':>7}  status")
        for u in users:
            n = len(db.scalars(select(Device.id).where(Device.user_id == u.id, Device.revoked_at.is_(None))).all())
            print(
                f"{u.username:<20} {u.display_name:<24} {'admin' if u.is_admin else 'member':<7} {n:>7}  "
                f"{'removed' if u.disabled_at else 'active'}"
            )
    return 0


def cmd_remove_user(args, settings: Settings) -> int:
    from sqlalchemy import func

    from app.models import User
    from app.services import accounts, audit

    factory = _open(settings)
    with factory() as db:
        user = db.scalar(select(User).where(User.username == args.username.lower(), User.disabled_at.is_(None)))
        if user is None:
            print(f"no active user {args.username!r}", file=sys.stderr)
            return 1
        admins = db.scalar(
            select(func.count()).select_from(User).where(User.is_admin.is_(True), User.disabled_at.is_(None))
        )
        if user.is_admin and admins <= 1 and not args.force:
            print("refusing to remove the only administrator (use --force)", file=sys.stderr)
            return 1
        accounts.disable_user(db, user)
        audit.record(db, "user_removed", target_user=user.id, was_admin=user.is_admin, via="cli")
        db.commit()
    print(f"removed {args.username}: sessions revoked, devices deleted, removed from conversations")
    return 0


def cmd_audit(args, settings: Settings) -> int:
    from app.services import audit

    factory = _open(settings)
    with factory() as db:
        events = audit.list_events(db, None, args.limit)
    for e in reversed(events):  # oldest first, so the newest line is the last one
        when = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(e.at / 1000))
        parts = [f"actor={e.actor_user_id or '-'}", f"target_user={e.target_user_id or '-'}"]
        if e.target_id:
            parts.append(f"target={e.target_id}")
        parts.append(f"ip={e.ip or '-'}")
        details = " ".join(f"{k}={v}" for k, v in sorted((e.details or {}).items()))
        print(f"{when} UTC  #{e.id:<6} {e.action:<20} {' '.join(parts)}" + (f"  {details}" if details else ""))
    return 0


def cmd_backup(args, settings: Settings) -> int:
    from app.services.backup import create_backup

    path = create_backup(settings, Path(args.out_dir))
    print(path.resolve())
    return 0


def cmd_restore(args, settings: Settings) -> int:
    from app.services.backup import restore_backup

    result = restore_backup(settings, Path(args.archive), force=args.force)
    c = result["counts"]
    print(
        f"restored: {c.get('users', 0)} users, {c.get('conversations', 0)} conversations, "
        f"{c.get('messages', 0)} messages, {c.get('attachment_files', 0)} attachment files",
        file=sys.stderr,
    )
    if result["previous_data"]:
        print(f"previous data kept in {result['previous_data']}", file=sys.stderr)
    print("restore complete; start the server (migrations run on start)")
    return 0


def cmd_seed_dev(args, settings: Settings) -> int:
    if settings.env != "development":
        print("seed-dev only runs with FAMILYCHAT_ENV=development", file=sys.stderr)
        return 1
    import uuid

    from app import clock
    from app.models import Conversation, ConversationMember, User
    from app.security import hash_password
    from app.services import conversations as convs

    factory = _open(settings)
    with factory() as db:
        users = {}
        for username, display in (("alice", "Alice"), ("bob", "Bob"), ("carol", "Carol")):
            user = db.scalar(select(User).where(User.username == username))
            if user is None:
                password = args.password or secrets.token_urlsafe(12)
                user = User(
                    id=str(uuid.uuid4()),
                    username=username,
                    display_name=display,
                    password_hash=hash_password(settings, password),
                    is_admin=username == "alice",
                    created_at=clock.now_ms(),
                )
                db.add(user)
                db.flush()
                shown = password if not args.password else "(as given with --password)"
                print(f"created {username}: password {shown}")
            else:
                print(f"{username} exists")
            users[username] = user
        alice, bob, carol = users["alice"], users["bob"], users["carol"]
        _, created = convs.create_direct(db, alice.id, bob.id)
        print("direct conversation alice-bob " + ("created" if created else "exists"))
        group = db.scalar(select(Conversation).where(Conversation.type == "group", Conversation.name == "Familjen"))
        if group is None:
            convs.create_group(db, alice.id, "Familjen", [bob.id, carol.id], None)
            print('group "Familjen" created (alice admin; bob, carol)')
        else:
            if not db.get(ConversationMember, (group.id, carol.id)):
                print('group "Familjen" exists (membership unchanged)')
            else:
                print('group "Familjen" exists')
        db.commit()
    print(
        "No messages were seeded: message content is end-to-end encrypted on the phones, so the server "
        "cannot create any. Log in on two devices and send messages from there.",
        file=sys.stderr,
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m app.cli", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("create-invite", help="create an invitation; the LAST stdout line is the code")
    p.add_argument("--admin", action="store_true", help="the new account becomes an administrator")
    p.add_argument("--note", default=None)
    p.add_argument("--hours", type=int, default=72, help="validity in hours (1..720, default 72)")
    p.set_defaults(func=cmd_create_invite)

    p = sub.add_parser("list-users")
    p.set_defaults(func=cmd_list_users)

    p = sub.add_parser("remove-user")
    p.add_argument("username")
    p.add_argument("--force", action="store_true", help="allow removing the only administrator")
    p.set_defaults(func=cmd_remove_user)

    p = sub.add_parser("audit", help="print the newest audit log entries (oldest first)")
    p.add_argument("--limit", type=int, default=50, help="how many entries (1..1000, default 50)")
    p.set_defaults(func=cmd_audit)

    p = sub.add_parser("backup", help="write a timestamped .tar.gz (consistent DB copy + attachments + manifest)")
    p.add_argument("out_dir")
    p.set_defaults(func=cmd_backup)

    p = sub.add_parser("restore", help="restore an archive into the configured data directory")
    p.add_argument("archive")
    p.add_argument("--force", action="store_true", help="replace existing data (kept in pre-restore-*)")
    p.set_defaults(func=cmd_restore)

    p = sub.add_parser("seed-dev", help="development only: users alice/bob/carol, a direct chat and a group")
    p.add_argument("--password", default=None, help="password for all seeded users (default: random, printed)")
    p.set_defaults(func=cmd_seed_dev)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if getattr(args, "hours", 72) < 1 or getattr(args, "hours", 72) > 720:
        print("--hours must be 1..720", file=sys.stderr)
        return 2
    if getattr(args, "command", "") == "audit" and not 1 <= args.limit <= 1000:
        print("--limit must be 1..1000", file=sys.stderr)
        return 2
    try:
        return args.func(args, _settings())
    except (BackupError, ConfigError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
