#!/usr/bin/env python3
"""Disposable reviewer demo. Python's standard library is the only script dependency."""
import argparse
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "target" / "demo"
# Fixed project/ports keep this database separate from normal local development.
ENV = {key: value for key, value in os.environ.items() if not key.upper().startswith(
    ("SPRING_", "APP_", "DISPATCH_", "MANAGEMENT_", "SERVER_", "DB_", "LOCAL_ADMIN_", "JWT_"))}
ENV.update(DB_PORT="55432", DB_NAME="ride_batching_demo", DB_USER="postgres",
           DB_PASSWORD="disposable-demo-database", DB_HOST="localhost", SERVER_PORT="18080",
           JWT_SECRET="disposable-demo-signing-material-never-use-in-production",
           SPRING_PROFILES_ACTIVE="demo", LOCAL_ADMIN_ENABLED="false")
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / ".env.example"),
           "--project-name", "ride-batching-demo", "-f", str(ROOT / "docker-compose.yml")]


def command(args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, env=ENV, text=True, **kwargs)
    if result.returncode:
        detail = (result.stderr or "").strip() if isinstance(result.stderr, str) else ""
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n{detail}")
    return result


def app_port_in_use():
    with socket.socket() as sock:
        return sock.connect_ex(("127.0.0.1", 18080)) == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["reset", "run", "stop"])
    parser.add_argument("--hold", action="store_true", help="Keep HTTP server running after PASS; Ctrl+C stops it cleanly")
    args = parser.parse_args()
    if not shutil.which("docker"):
        raise RuntimeError("Docker is missing. Install Docker with Compose, then start its daemon.")
    command(["docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if args.action == "reset":
        if app_port_in_use():
            raise RuntimeError("Port 18080 is in use. Stop the held demo with python scripts/demo/demo.py stop before resetting.")
        # Only the explicitly named demo Compose project and its volume are removed.
        print("Removing only ride-batching-demo containers and its disposable PostgreSQL volume.", flush=True)
        command(COMPOSE + ["down", "--volumes", "--remove-orphans"])
        return
    if args.action == "stop":
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "stop").touch()
        command(COMPOSE + ["stop"])
        return
    if not shutil.which("java"):
        raise RuntimeError("JDK 17+ is required. Put java and javac on PATH.")
    if app_port_in_use():
        raise RuntimeError("Port 18080 is already in use. Stop the previous demo/application first.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "stop").unlink(missing_ok=True)
    command(COMPOSE + ["up", "-d", "--wait", "--wait-timeout", "90"])
    table = command(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "ride_batching_demo",
                    "-Atc", "SELECT to_regclass('public.employees') IS NOT NULL"], capture_output=True).stdout.strip()
    if table == "t":
        count = command(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "ride_batching_demo",
                        "-Atc", "SELECT count(*) FROM employees"], capture_output=True).stdout.strip()
        if count != "0":
            raise RuntimeError("Demo state is already populated. Stop any held run, then run: python scripts/demo/demo.py reset")
    for artifact in OUTPUT.glob("*.json"):
        artifact.unlink()
    (OUTPUT / "evidence.md").unlink(missing_ok=True)
    (OUTPUT / "prometheus.txt").unlink(missing_ok=True)
    wrapper = str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    invocation = [wrapper, "-B", "-Dtest=ReviewerDemoTest", "-Ddemo.enabled=true",
                  f"-Ddemo.hold={str(args.hold).lower()}", "test"]
    print("Starting the real Spring Boot HTTP server and asserted scenario. First run downloads Maven dependencies.", flush=True)
    print(f"Full logs: {OUTPUT / 'application.log'}", flush=True)
    # Inherit terminal input; the Java runner never depends on stdin or shell tools.
    with (OUTPUT / "application.log").open("w", encoding="utf-8") as log:
        isolation = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        process = subprocess.Popen(invocation, cwd=ROOT, env=ENV, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, errors="replace", **isolation)
        try:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if "[DEMO]" in line or "[ERROR]" in line or "BUILD SUCCESS" in line:
                    print(line.rstrip(), flush=True)
            code = process.wait()
        except KeyboardInterrupt:
            (OUTPUT / "stop").touch()
            print("Stopping the demo HTTP server...", flush=True)
            try:
                code = process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
                raise RuntimeError("Demo was interrupted. Inspect application.log; reset before retrying.")
    if code:
        tail = (OUTPUT / "application.log").read_text(encoding="utf-8").splitlines()[-45:]
        print("\n".join(tail), file=sys.stderr)
        raise RuntimeError("Scenario failed. See target/demo/application.log and Surefire reports. Run reset before retrying.")
    print(f"Verified evidence: {OUTPUT / 'evidence.md'}", flush=True)
    print("PostgreSQL remains available on localhost:55432. Use reset for a fresh repeat.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError) as error:
        print(f"DEMO FAILED: {error}", file=sys.stderr)
        sys.exit(1)
