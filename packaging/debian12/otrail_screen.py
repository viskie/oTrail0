#!/usr/bin/python3
"""Manage a localhost-only X11 VNC session tunneled to one authorized Android device."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from dataclasses import asdict, dataclass


PORT = 5900
WINDOW_ID = re.compile(r"^0x[0-9a-fA-F]+$")


@dataclass(frozen=True)
class Window:
    window_id: str
    title: str


def state_path():
    base = os.environ.get("XDG_RUNTIME_DIR")
    if base and Path(base).is_dir():
        return Path(base) / "otrail-screen" / "session.json"
    return Path.home() / ".cache" / "otrail-screen" / "session.json"


def parse_windows(output):
    windows = []
    for line in output.splitlines():
        fields = line.split(None, 7)
        if len(fields) != 8 or not WINDOW_ID.fullmatch(fields[0]):
            continue
        title = fields[7].strip()
        if title:
            windows.append(Window(fields[0], title))
    return windows


def list_windows():
    if not os.environ.get("DISPLAY"):
        raise RuntimeError(
            "No X11 DISPLAY is available. Start this command inside the logged-in "
            "desktop session; native Wayland window enumeration is not supported."
        )
    if not shutil.which("wmctrl"):
        raise RuntimeError("wmctrl is required; install the Debian package dependency.")
    result = subprocess.run(
        ["wmctrl", "-l", "-G"],
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    )
    return parse_windows(result.stdout)


def select_window(windows, number):
    try:
        index = int(number)
    except (TypeError, ValueError) as error:
        raise ValueError("Window selection must be a positive list number.") from error
    if index < 1 or index > len(windows):
        raise ValueError(f"Window number must be between 1 and {len(windows)}.")
    return windows[index - 1]


def adb_command(serial, *args, timeout=15):
    if not shutil.which("adb"):
        raise RuntimeError("adb is required; install the Debian package dependency.")
    command = ["adb"]
    if serial:
        command.extend(["-s", serial])
    try:
        return subprocess.run(
            command + list(args),
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or str(error)).strip()
        raise RuntimeError(f"ADB command failed: {detail}") from error


def choose_device(serial):
    output = adb_command(None, "devices").stdout
    devices = [
        line.split()[0]
        for line in output.splitlines()[1:]
        if len(line.split()) >= 2 and line.split()[1] == "device"
    ]
    if serial:
        if serial not in devices:
            raise RuntimeError(
                f"ADB device {serial!r} is not authorized and online; check USB debugging."
            )
        return serial
    if not devices:
        raise RuntimeError(
            "No authorized Android device found. Connect USB, enable USB debugging, "
            "and accept the laptop authorization prompt."
        )
    if len(devices) != 1:
        raise RuntimeError(
            "More than one ADB device is connected; rerun with --serial SERIAL."
        )
    return devices[0]


def proc_identity(pid):
    try:
        command = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode()
        stat = Path(f"/proc/{pid}/stat").read_text()
    except FileNotFoundError:
        return None
    end = stat.rfind(")")
    fields = stat[end + 2 :].split()
    return {"command": command, "start_time": fields[19] if len(fields) > 19 else ""}


def read_session(path=None):
    path = path or state_path()
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        return None
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read session state at {path}: {error}") from error


def write_session(session, path=None):
    path = path or state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(session, indent=2) + "\n")
    temporary.chmod(0o600)
    temporary.replace(path)


def stop_session(path=None):
    path = path or state_path()
    session = read_session(path)
    if not session:
        print("No oTraiL screen session is recorded.")
        return
    pid = int(session["pid"])
    identity = proc_identity(pid)
    if identity and "x11vnc" in identity["command"]:
        if identity["start_time"] != session.get("process_start_time"):
            raise RuntimeError("The recorded x11vnc PID has been reused; refusing to signal it.")
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 5
        while proc_identity(pid) and time.monotonic() < deadline:
            time.sleep(0.1)
        identity = proc_identity(pid)
        if identity and identity["start_time"] == session.get("process_start_time"):
            os.kill(pid, signal.SIGKILL)
    path.unlink(missing_ok=True)
    serial = session.get("serial")
    if serial:
        try:
            adb_command(serial, "reverse", "--remove", f"tcp:{session['port']}")
        except (subprocess.CalledProcessError, RuntimeError) as error:
            print(f"Warning: VNC stopped but ADB reverse cleanup failed: {error}", file=sys.stderr)
    print("Screen-sharing session stopped.")


def start_session(number, serial=None, path=None):
    path = path or state_path()
    if read_session(path):
        raise RuntimeError("A session is already recorded; use status, switch, or stop first.")
    if not shutil.which("x11vnc"):
        raise RuntimeError("x11vnc is required; install the Debian package dependency.")
    windows = list_windows()
    selected = select_window(windows, number)
    device = choose_device(serial)
    if os.environ.get("XDG_SESSION_TYPE") == "wayland":
        print(
            "Wayland session detected: only XWayland windows exposed to wmctrl can be "
            "shared. Native Wayland windows are not available through this X11 service.",
            file=sys.stderr,
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    log_path = path.parent / "x11vnc.log"
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=0.25):
            raise RuntimeError(f"Port {PORT} is already in use on localhost.")
    except ConnectionRefusedError:
        pass
    except OSError as error:
        raise RuntimeError(f"Cannot check localhost:{PORT}: {error}") from error
    with log_path.open("ab") as log:
        process = subprocess.Popen(
            [
                "x11vnc",
                "-norc",
                "-display",
                os.environ["DISPLAY"],
                "-id",
                selected.window_id,
                "-localhost",
                "-nopw",
                "-rfbport",
                str(PORT),
                "-forever",
                "-shared",
                "-xkb",
                "-quiet",
            ],
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    try:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(
                    f"x11vnc exited with status {process.returncode}; see {log_path}."
                )
            try:
                with socket.create_connection(("127.0.0.1", PORT), timeout=0.25) as probe:
                    probe.settimeout(1)
                    banner = bytearray()
                    while len(banner) < 12:
                        chunk = probe.recv(12 - len(banner))
                        if not chunk:
                            break
                        banner.extend(chunk)
                    if bytes(banner).startswith(b"RFB 003."):
                        break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError(f"x11vnc did not listen on localhost:{PORT}; see {log_path}.")
        adb_command(device, "reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        identity = proc_identity(process.pid)
        if not identity:
            raise RuntimeError("Cannot verify the started x11vnc process.")
        write_session(
            {
                "pid": process.pid,
                "process_start_time": identity["start_time"],
                "window_id": selected.window_id,
                "window_title": selected.title,
                "serial": device,
                "port": PORT,
                "started_at": int(time.time()),
            },
            path,
        )
    except Exception:
        try:
            adb_command(device, "reverse", "--remove", f"tcp:{PORT}")
        except (OSError, RuntimeError, subprocess.SubprocessError) as cleanup_error:
            print(f"Warning: could not remove ADB reverse mapping: {cleanup_error}", file=sys.stderr)
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
        raise
    print(f"Sharing window {selected.window_id}: {selected.title}")
    print(f"ADB reverse configured for authorized device {device}.")
    print("Open oTraiL → desktop icon → Connect.")


def show_status(path=None):
    session = read_session(path)
    if not session:
        print("No oTraiL screen session is recorded.")
        return
    identity = proc_identity(int(session["pid"]))
    if not identity or identity["start_time"] != session.get("process_start_time"):
        print("Recorded session is no longer running; use stop to clear stale state.")
        return
    print(f"Sharing window {session['window_id']}: {session['window_title']}")
    print(f"PID {session['pid']} · localhost:{session['port']} · ADB device {session['serial']}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="otrail-screen",
        description="Share a numbered X11 window with oTraiL over an authorized USB ADB tunnel.",
    )
    parser.add_argument("--serial", help="ADB serial when more than one device is connected")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("windows", help="list X11 windows in selection order")
    start_parser = subparsers.add_parser("start", help="share a numbered window")
    start_parser.add_argument("number")
    switch_parser = subparsers.add_parser("switch", help="stop and share a different window")
    switch_parser.add_argument("number")
    subparsers.add_parser("status", help="show the current sharing session")
    subparsers.add_parser("stop", help="stop sharing and remove the ADB reverse tunnel")
    args = parser.parse_args(argv)
    try:
        if args.command == "windows":
            for index, window in enumerate(list_windows(), 1):
                print(f"{index:>3}  {window.window_id}  {window.title}")
        elif args.command == "start":
            start_session(args.number, args.serial)
        elif args.command == "switch":
            select_window(list_windows(), args.number)
            if read_session():
                stop_session()
            start_session(args.number, args.serial)
        elif args.command == "status":
            show_status()
        elif args.command == "stop":
            stop_session()
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print(f"otrail-screen: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
