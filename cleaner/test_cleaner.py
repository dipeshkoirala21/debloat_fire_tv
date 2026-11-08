#!/usr/bin/env python3
"""Host tests for the device shell cleaner, with fake Android commands. No TV needed."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest


MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, sys, time
root = pathlib.Path(os.environ["MOCK_ROOT"])
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
def record(value):
    with (root / "calls").open("a") as f:
        f.write(json.dumps(value) + "\n")
if name == "logcat":
    record(["logcat", os.getpid()])
    cursor = int((root / "cursor").read_text())
    while True:
        lines = (root / "input").read_text().splitlines()
        while cursor < len(lines):
            line = lines[cursor]
            cursor += 1
            (root / "cursor").write_text(str(cursor))
            if line == "__EOF__":
                sys.exit(1)
            print(line, flush=True)
        time.sleep(.01)
elif name == "dumpsys":
    print((root / "front").read_text())
elif name == "pidof":
    sys.exit(0 if args[0] in (root / "running").read_text().split() else 1)
elif name == "am":
    # Child commands must not inherit the event stream, even if they read stdin.
    consumed = sys.stdin.read()
    record(["am", *args, "stdin=" + consumed])
    if args[0] == "force-stop":
        switch = root / "switch_front"
        if switch.exists():
            (root / "front").write_text(switch.read_text())
        failed = root / "fail_package"
        if failed.exists() and args[-1] == failed.read_text():
            sys.exit(1)
elif name == "sleep":
    time.sleep(.025)
'''


class CleanerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tvhome-cleaner-test-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        mock = self.bin / "mock"
        mock.write_text(MOCK)
        mock.chmod(0o755)
        for name in ("logcat", "dumpsys", "pidof", "am", "sleep"):
            (self.bin / name).symlink_to(mock)
        for name, text in {
            "input": "", "cursor": "0", "calls": "",
            "front": "mResumedActivity: ActivityRecord{0 u0 com.custom.tvhome/.HomeActivity t1}",
            "running": "org.test.one org.test.two",
        }.items():
            (self.root / name).write_text(text)
        self.proc = None

    def tearDown(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
                self.fail("Cleaner did not shut down after SIGTERM")
        self.temp.cleanup()

    def start(self, apps="org.test.one org.test.two"):
        source = Path(__file__).with_name("firetv_cleaner.sh").read_text()
        source = re.sub(r'^APPS=.*$', 'APPS="' + apps + '"', source, flags=re.M)
        script = self.root / "cleaner.sh"
        script.write_text(source)
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   MOCK_ROOT=str(self.root), CLEANER_STATE_DIR=str(self.root / "state"),
                   CLEANER_LOG_FILE=str(self.root / "cleaner.log"))
        self.proc = subprocess.Popen(["/bin/sh", str(script)], env=env,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def send(self, line):
        with (self.root / "input").open("a") as f:
            f.write(line + "\n")

    def request(self, command="CLEAN", number=1):
        request_id = f"{number:032x}"
        self.send(f"I/TVHomeCleaner( 123): {command} {request_id}")
        return request_id

    def wait_for(self, predicate, message="condition", timeout=3):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if predicate():
                return
            time.sleep(.015)
        self.fail("Timed out waiting for " + message)

    def calls(self):
        return [json.loads(line) for line in (self.root / "calls").read_text().splitlines()]

    def replies(self):
        return [call for call in self.calls() if call[:2] == ["am", "broadcast"]]

    def stops(self):
        return [call for call in self.calls() if call[:2] == ["am", "force-stop"]]

    def response(self, expected):
        status = self.root / "state" / "last_request"
        self.wait_for(lambda: status.exists() and status.read_text().strip() == expected,
                      expected)
        self.wait_for(lambda: any(expected.split()[0] in call for call in self.replies()),
                      "matching broadcast")

    def test_clean_reports_success_and_keeps_event_stdin_private(self):
        self.start()
        request = self.request()
        self.response(request + " cleaned 2")
        ping = self.request("PING", 2)
        self.response(ping + " ready 0")
        self.assertEqual([call[-2] for call in self.stops()], ["org.test.one", "org.test.two"])
        self.assertTrue(all(call[-1] == "stdin=" for call in self.calls() if call[0] == "am"))
        self.assertTrue(all("com.custom.tvhome" in call for call in self.replies()))

    def test_logcat_exit_restarts_and_serves_requests(self):
        self.start()
        self.send("__EOF__")
        request = self.request("PING")
        self.response(request + " ready 0")
        self.assertGreaterEqual(sum(call[0] == "logcat" for call in self.calls()), 2)
        self.assertIn("reconnecting", (self.root / "cleaner.log").read_text())

    def test_same_request_id_can_probe_then_clean(self):
        self.start()
        request = self.request("PING")
        self.response(request + " ready 0")
        self.request("CLEAN")
        self.response(request + " cleaned 2")
        self.wait_for(lambda: len(self.replies()) == 2, "probe and cleanup broadcasts")
        self.assertEqual(len(self.stops()), 2)

    def test_background_request_is_skipped(self):
        (self.root / "front").write_text("mResumedActivity: org.test.one/.Player")
        self.start()
        request = self.request()
        self.response(request + " skipped 0")
        self.assertEqual(self.stops(), [])

    def test_foreground_rechecked_between_apps(self):
        (self.root / "switch_front").write_text("topResumedActivity: org.test.two/.Player")
        self.start()
        request = self.request()
        self.response(request + " skipped 1")
        self.assertEqual(len(self.stops()), 1)

    def test_top_resumed_activity_is_supported(self):
        (self.root / "front").write_text("topResumedActivity: com.custom.tvhome/.HomeActivity")
        self.start()
        request = self.request()
        self.response(request + " cleaned 2")

    def test_failed_force_stop_is_not_counted(self):
        (self.root / "fail_package").write_text("org.test.one")
        self.start()
        request = self.request()
        self.response(request + " error 1")

    def test_invalid_requests_and_replayed_request_do_nothing(self):
        self.start()
        for line in (
            "I/TVHomeCleaner(1): CLEAN nope",
            "I/TVHomeCleaner(1): SHELL " + "a" * 32,
            "I/TVHomeCleaner(1): CLEAN " + "a" * 31 + ";",
            "I/TVHomeCleaner(1): CLEAN " + "a" * 32 + " extra",
        ):
            self.send(line)
        request = self.request()
        self.response(request + " cleaned 2")
        self.request()
        ping = self.request("PING", 2)
        self.response(ping + " ready 0")
        self.assertEqual(len(self.stops()), 2)
        self.assertEqual(len(self.replies()), 2)

    def test_legacy_home_event_still_cleans_without_reply(self):
        self.start()
        self.send("I/ActivityManager(1): START u0 {cmp=com.custom.tvhome/.HomeActivity}")
        self.wait_for(lambda: len(self.stops()) == 2, "automatic cleanup")
        self.assertEqual(self.replies(), [])

    def test_empty_app_set_is_valid(self):
        self.start("")
        request = self.request()
        self.response(request + " cleaned 0")
        self.assertEqual(self.stops(), [])

    def test_protected_package_refused(self):
        self.start("org.test.one com.amazon.tv.launcher")
        self.assertEqual(self.proc.wait(timeout=3), 1)
        self.assertEqual(self.stops(), [])
        self.assertIn("Refusing", (self.root / "cleaner.log").read_text())

    def test_shutdown_reaps_logcat_and_clears_state(self):
        self.start()
        self.wait_for(lambda: any(c[0] == "logcat" for c in self.calls()), "logcat startup")
        child = next(c[1] for c in self.calls() if c[0] == "logcat")
        self.proc.terminate()
        self.assertEqual(self.proc.wait(timeout=3), 0)
        self.assertFalse((self.root / "state").exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(child, 0)


class StartCleanerTest(unittest.TestCase):
    def run_start(self, app_literal):
        with tempfile.TemporaryDirectory(prefix="tvhome-start-test-") as directory:
            root = Path(directory)
            cleaner = root / "cleaner"
            cleaner.mkdir()
            for filename in ("start_cleaner.sh", "firetv_cleaner.sh", "firetv_cleaner_supervisor.sh"):
                shutil.copyfile(Path(__file__).with_name(filename), cleaner / filename)
            (root / "config.sh").write_text(
                "APPS=" + app_literal + "\ntv_addr() { echo \"$1:5555\"; }\n")
            bin_path = root / "bin"
            bin_path.mkdir()
            (bin_path / "adb").write_text(r'''#!/usr/bin/env python3
import os, pathlib, shlex, sys
root = pathlib.Path(os.environ["MOCK_ROOT"])
args = sys.argv[1:]
if args[0] == "connect":
    sys.exit(0)
args = args[2:]
if args[0] == "push" and args[2] == "/data/local/tmp/firetv_cleaner.sh":
    (root / "pushed").write_text(pathlib.Path(args[1]).read_text())
elif args[0] == "shell":
    command = args[1:]
    if len(command) == 1 and command[0].startswith("log "):
        request = shlex.split(command[0])[-1].split()[-1]
        (root / "reply").write_text(request + " ready 0")
    if command and command[0] == "cat":
        print((root / "reply").read_text())
''')
            (bin_path / "sleep").write_text("#!/bin/sh\nexit 0\n")
            for executable in bin_path.iterdir():
                executable.chmod(0o755)
            result = subprocess.run(
                ["/bin/bash", str(cleaner / "start_cleaner.sh"), "192.0.2.1"],
                env=dict(os.environ, PATH=str(bin_path) + os.pathsep + os.environ["PATH"],
                         MOCK_ROOT=str(root)), capture_output=True, text=True, timeout=5)
            pushed = (root / "pushed").read_text() if (root / "pushed").exists() else None
            return result, pushed

    def test_empty_bash_array_pushes_and_verifies_ping(self):
        result, pushed = self.run_start("()")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('APPS=""', pushed)
        self.assertIn("are responding", result.stdout)

    def test_protected_packages_never_get_pushed(self):
        result, pushed = self.run_start("(com.custom.tvhome)")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(pushed)
        self.assertIn("protected", result.stderr)

    def test_shell_metacharacters_never_get_pushed(self):
        result, pushed = self.run_start("('org.test.app;touch')")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(pushed)
        self.assertIn("Invalid", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
