#!/usr/bin/env python3
"""Exercise Fire OS setsid's forking behavior and overlapping readiness replies."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

class SupervisorTest(unittest.TestCase):
    def test_forked_worker_and_competing_probe(self):
        with tempfile.TemporaryDirectory(prefix='tvhome-supervisor-') as directory:
            root = Path(directory)
            (root/'bin').mkdir()
            source = Path(__file__).with_name('firetv_cleaner_supervisor.sh').read_text()
            (root/'supervisor.sh').write_text(source.replace('/data/local/tmp', directory))
            (root/'firetv_cleaner.sh').write_text('''#!/bin/sh
umask 077
mkdir "$MOCK_ROOT/tvhome_cleaner"
echo $$ > "$MOCK_ROOT/tvhome_cleaner/pid"
trap 'exit 0' TERM INT
while :; do sleep 1; done
''')
            mocks = {
                'setsid': '''#!/usr/bin/env python3
import os,sys
if os.fork(): sys.exit(0)
os.setsid()
os.execvp(sys.argv[1],sys.argv[1:])
''',
                'sleep': '#!/usr/bin/env python3\nimport time\ntime.sleep(.04)\n',
                'log': '''#!/usr/bin/env python3
import os,pathlib
# A concurrent installer PING was handled last. Its ID is different, but this
# response still proves that the freshly launched worker handled a request.
(pathlib.Path(os.environ['MOCK_ROOT'])/'tvhome_cleaner/last_request').write_text('f'*32+' ready 0\\n')
''',
                'logcat': '#!/usr/bin/env python3\nimport time\ntime.sleep(60)\n',
                'am': '#!/bin/sh\nexit 0\n'
            }
            for name,content in mocks.items():
                file=root/'bin'/name
                file.write_text(content)
                file.chmod(0o755)
            proc=subprocess.Popen(['/bin/sh',str(root/'supervisor.sh')],
                env=dict(os.environ,MOCK_ROOT=directory,PATH=str(root/'bin')+os.pathsep+os.environ['PATH']),
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            worker=None
            try:
                ready=root/'tvhome_cleaner_supervisor/ready'
                deadline=time.monotonic()+5
                while not ready.exists() and proc.poll() is None and time.monotonic()<deadline:time.sleep(.02)
                self.assertIsNone(proc.poll(),'Supervisor mistook the exited setsid launcher for its worker')
                self.assertTrue(ready.exists(),'Supervisor rejected a concurrent ready response')
                worker=int(ready.read_text())
                self.assertEqual(worker,int((root/'tvhome_cleaner/pid').read_text()))
                os.kill(worker,0)
                os.kill(worker,signal.SIGKILL)
                deadline=time.monotonic()+5
                while time.monotonic()<deadline:
                    if ready.exists() and ready.read_text().strip() not in ('',str(worker)):break
                    time.sleep(.02)
                self.assertTrue(ready.exists())
                replacement=int(ready.read_text())
                self.assertNotEqual(worker,replacement,'Supervisor did not replace a dead worker')
                worker=replacement
                os.kill(worker,0)
            finally:
                proc.terminate()
                try:proc.wait(timeout=5)
                except subprocess.TimeoutExpired:proc.kill();proc.wait()
                if worker:
                    try:os.killpg(worker,signal.SIGTERM)
                    except ProcessLookupError:pass

if __name__=='__main__':unittest.main(verbosity=2)
