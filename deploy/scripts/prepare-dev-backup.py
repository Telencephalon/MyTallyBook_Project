"""Interactive SSH launcher: no passwords in source, arguments, reports or files."""
import base64
import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import warnings
import zlib

import paramiko

ROOT = Path(__file__).resolve().parents[2]
HOST = "117.72.101.42"


def hidden_password(label):
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        value = getpass.getpass(label)
    if not value:
        raise RuntimeError("Empty password; operation cancelled before connection")
    return value


def v1_checksum(path):
    # Matches Flyway 12.4 ChecksumCalculator: UTF-8 CRC32 over lines, excluding EOL/BOM.
    checksum = 0
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        checksum = zlib.crc32(line.encode("utf-8"), checksum)
    return checksum if checksum < 2**31 else checksum - 2**32


def private_output_directory():
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    path = ROOT / "backup" / "dev-v2" / stamp
    path.mkdir(parents=True, exist_ok=False)
    if os.name == "nt":
        user = subprocess.check_output(["whoami.exe"], text=True).strip()
        subprocess.run(["icacls.exe", str(path), "/inheritance:r", "/grant:r",
                        user + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F",
                        "*S-1-5-32-544:(OI)(CI)F"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return path


def verify_remote_tools(client, command, output):
    stdin, stdout, stderr = client.exec_command(command, timeout=60)
    stdin.write(json.dumps({"action": "check_tools"}) + "\n")
    stdin.flush()
    stdin.channel.shutdown_write()
    ready = False
    with (output / "events.jsonl").open("x", encoding="utf-8") as log:
        for line in stdout:
            event = json.loads(line)
            log.write(json.dumps(event) + "\n")
            log.flush()
            print(json.dumps(event), flush=True)
            ready = ready or event.get("event") == "tools_ready"
    exit_code = stdout.channel.recv_exit_status()
    stderr.read()
    return exit_code == 0 and ready


def main():
    if not sys.stdin.isatty():
        raise RuntimeError("A visible interactive console is required")
    print("=== MyTallyBook development preflight / backup / restore rehearsal ===")
    print("Development: read and back up account_book_dev only. No V2 migration.")
    print("Test: restore into EMPTY account_book_test, verify, then clean our restored tables.")
    client = paramiko.SSHClient()
    client.load_host_keys(str(Path.home() / ".ssh" / "known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    ssh_password = hidden_password("Cloud SSH root password (hidden): ")
    try:
        client.connect(HOST, port=22, username="root", password=ssh_password,
                       look_for_keys=False, allow_agent=False, timeout=15,
                       banner_timeout=15, auth_timeout=30)
    finally:
        ssh_password = None
    try:
        print("SSH authenticated; saved server host key matched.", flush=True)
        client.get_transport().set_keepalive(30)
        output = private_output_directory()
        print("Local protected output: " + str(output), flush=True)
        remote = (Path(__file__).with_name("prepare-dev-backup-remote.py")).read_bytes()
        encoded = base64.b64encode(remote).decode("ascii")
        command = 'python3 -u -c "import base64;exec(base64.b64decode(\'' + encoded + '\'))"'
        if not verify_remote_tools(client, command, output):
            print("Client tools are not ready. Stopped before requesting database passwords.")
            return 1
        dev_password = hidden_password("account_book_dev_app password (hidden): ")
        test_password = hidden_password("account_book_test_app password (hidden): ")
        stdin, stdout, stderr = client.exec_command(command, timeout=600)
        request = {"dev_password": dev_password, "test_password": test_password,
                   "v1_checksum": v1_checksum(ROOT / "account-book-server/src/main/resources/db/migration/V1__init_schema.sql")}
        stdin.write(json.dumps(request) + "\n")
        stdin.flush()
        stdin.channel.shutdown_write()
        request.clear()
        dev_password = test_password = None
        complete = None
        with (output / "events.jsonl").open("a", encoding="utf-8") as log:
            for line in stdout:
                event = json.loads(line)
                log.write(json.dumps(event) + "\n")
                log.flush()
                print(json.dumps(event), flush=True)
                if event.get("event") == "complete":
                    complete = event
        exit_code = stdout.channel.recv_exit_status()
        # Never print unstructured remote stderr; failures above are deliberately sanitized.
        stderr.read()
        if exit_code or not complete:
            print("Preparation stopped. See sanitized events.jsonl; no development migration was run.")
            return 1
        backup = complete["backup"]
        if not re.fullmatch(r"/var/backups/mytallybook-dev-v2-[A-Za-z0-9_-]+/account_book_dev-v1\.sql", backup):
            raise RuntimeError("Unexpected remote backup path")
        with client.open_sftp() as sftp:
            sftp.get(backup, str(output / "account_book_dev-v1.sql"))
        local_hash = hashlib.sha256((output / "account_book_dev-v1.sql").read_bytes()).hexdigest()
        if local_hash != complete["sha256"]:
            raise RuntimeError("Downloaded backup SHA256 mismatch")
        complete["local_backup"] = str(output / "account_book_dev-v1.sql")
        complete["download_verified"] = True
        (output / "manifest.json").write_text(json.dumps(complete, indent=2), encoding="utf-8")
        print("PREPARED_NOT_MIGRATED: backup and restore verified; local copy verified.")
        print("Manifest: " + str(output / "manifest.json"))
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Stopped safely. Error type: " + type(error).__name__)
        sys.exit(1)
