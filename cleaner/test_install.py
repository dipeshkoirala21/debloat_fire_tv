#!/usr/bin/env python3
"""Regression checks: an APK update must not leave the post-reboot worker offline."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

class InstallTest(unittest.TestCase):
    def run_install(self, boot='ready', worker_exit=0):
        with tempfile.TemporaryDirectory(prefix='tvhome-install-') as directory:
            root = Path(directory)
            (root/'launcher').mkdir()
            (root/'cleaner').mkdir()
            (root/'bin').mkdir()
            shutil.copyfile(Path(__file__).parents[1]/'launcher/install.sh', root/'launcher/install.sh')
            (root/'config.sh').write_text('APPS=()\ntv_addr() { echo "$1:5555"; }\n')
            scripts = {
                'launcher/build.sh': '#!/bin/sh\nexit 0\n',
                'cleaner/start_cleaner.sh': '#!/bin/sh\necho worker >> "$MOCK_ROOT/events"\nexit '+str(worker_exit)+'\n',
                'bin/sleep': '#!/bin/sh\nexit 0\n',
                'bin/adb': '''#!/usr/bin/env python3
import os,sys,pathlib
root=pathlib.Path(os.environ['MOCK_ROOT'])
args=sys.argv[1:]
with (root/'events').open('a') as f:f.write(' '.join(args)+'\\n')
if 'getprop' in args:
 count=int((root/'polls').read_text()) if (root/'polls').exists() else 0
 (root/'polls').write_text(str(count+1))
 print('1' if count>0 and os.environ['MOCK_BOOT']=='ready' else '0')
elif 'settings' in args and 'get' in args:print('null')
'''
            }
            for path, content in scripts.items():
                file=root/path
                file.write_text(content)
                file.chmod(0o755)
            result=subprocess.run(['/bin/bash',str(root/'launcher/install.sh'),'192.0.2.1'],
                env=dict(os.environ,PATH=str(root/'bin')+os.pathsep+os.environ['PATH'],
                         MOCK_ROOT=str(root),MOCK_BOOT=boot),capture_output=True,text=True,timeout=15)
            return result,(root/'events').read_text().splitlines()

    def test_reboot_then_ready_then_worker(self):
        result,events=self.run_install()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertGreater(events.index('worker'),next(i for i,e in enumerate(events) if e.endswith(' reboot')))
        self.assertGreaterEqual(sum('getprop' in e for e in events),2)
        self.assertIn('automatic RAM cleanup are ready',result.stdout)

    def test_offline_tv_is_not_reported_complete(self):
        result,events=self.run_install(boot='offline')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('worker',events)
        self.assertNotIn('Installation complete',result.stdout)

    def test_failed_worker_is_not_reported_complete(self):
        result,events=self.run_install(worker_exit=1)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('worker',events)
        self.assertNotIn('Installation complete',result.stdout)

if __name__=='__main__':unittest.main(verbosity=2)
