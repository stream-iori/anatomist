#!/usr/bin/env python3
"""Owned process fixtures: crash recovery, cleanup diagnostics, concurrency and retention."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from e2e_report import execute, track, workspace, run_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--keep-on-failure', action='store_true')
    args = parser.parse_args()
    runners = {}
    if args.jar:
        runners['jar'] = [args.java, '--enable-native-access=ALL-UNNAMED', '-jar', str(args.jar.resolve())]
    if args.native:
        runners['native'] = [str(args.native.resolve())]
    if not runners:
        parser.error('provide --jar and/or --native')
    report = track({'scenarios': []})
    real_git = shutil.which('git')
    for mode, binary in runners.items():
        with workspace('anatomist-lifecycle-' + mode + '-') as temporary, ExitStack() as cleanup:
            root = Path(temporary).resolve()
            project = root / 'project'
            project.mkdir()
            env = dict(os.environ, ANATOMIST_HOME=str(root / 'home'))

            def git(*argv):
                return run_command([real_git, '-C', str(project), *argv], capture_output=True, text=True, check=True).stdout.strip()

            def cli(*argv, environ=None, code=0):
                result = run_command(binary + list(argv), cwd=project, env=environ or env, capture_output=True, text=True, timeout=120)
                assert result.returncode == code, (argv, result.returncode, result.stderr[-4000:])
                return result

            def write(name, value):
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value)

            write('pom.xml', '<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>lifecycle</artifactId><version>1</version></project>')
            write('src/main/java/p/A.java', 'package p; public class A { public int value(){return 1;} }')
            write('.gitignore', 'target/\n.anatomist/index*\n')
            git('init', '-q');git('config', 'user.name', 'Lifecycle Test');git('config', 'user.email', 'lifecycle@example.invalid')
            git('add', '.');git('commit', '-qm', 'fixture')
            common = ['diff', '--base', 'HEAD', '--target', 'WORKTREE', '--no-classpath', '--java-version', '25', '--format', 'json', '--timings']
            first = json.loads(cli(*common).stdout)
            write('target/report.txt', 'ignored build output')
            second = json.loads(cli(*common).stdout)
            assert first['comparison']['target']['id'] == second['comparison']['target']['id']
            assert second['comparison']['timings']['preparation'].get('snapshot_builds', 0) == 0
            report['scenarios'].append(mode + ':ignored-output-reuse')
            snapshots = [first['comparison'][side]['id'] for side in ('base', 'target')]
            fixed = ['diff', '--base', 'snapshot:' + snapshots[0], '--target', 'snapshot:' + snapshots[1], '--no-build', '--format', 'json']
            tools = root / 'tools';tools.mkdir()
            ready = root / 'maven-ready'
            fake_maven = tools / 'mvn'
            fake_maven.write_text('#!' + sys.executable + '\nimport pathlib,time,os\npathlib.Path(' + repr(str(ready)) + ').write_text(str(os.getpid()))\ntime.sleep(90)\n')
            fake_maven.chmod(0o755)
            blocked_env = dict(env, PATH=str(tools) + os.pathsep + os.environ['PATH'])
            write('pom.xml', (project / 'pom.xml').read_text().replace('<version>1</version>', '<version>2</version>'))
            log = root / 'writer.log'
            with log.open('w') as output:
                process = subprocess.Popen(binary + ['index', '.', '--ref', 'WORKTREE', '--java-version', '25', '--format', 'json'], cwd=project, env=blocked_env, stdout=output, stderr=output, start_new_session=True)
                def stop_fixture_group():
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                cleanup.callback(stop_fixture_group)
                try:
                    deadline = time.monotonic() + 30
                    while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(.02)
                    assert ready.exists(), log.read_text()[-4000:]
                    started = time.monotonic()
                    assert json.loads(cli(*fixed).stdout)['contract'] == 'anatomist-diff/v2'
                    assert time.monotonic() - started < 10, 'cached diff waited for repository writer'
                finally:
                    process.kill()
                    process.wait(timeout=10)
            store = next((root / 'home/versions').iterdir())
            assert (store / 'workspace').exists()
            preview = json.loads(cli('snapshots', 'gc').stdout)
            assert preview['recovery'] and (store / 'workspace').exists()
            shutil.rmtree(store / 'workspace')  # Git registration deliberately survives.
            result = json.loads(cli('snapshots', 'gc', '--execute').stdout)
            assert result['recovery']
            assert any(item['kind'] == 'build_process' and item['owned'] for item in result['recovery'])
            assert len([line for line in git('worktree', 'list', '--porcelain').splitlines() if line.startswith('worktree ')]) == 1
            cli(*common)
            report['scenarios'].extend([mode + ':parallel-cached-read', mode + ':killed-build', mode + ':missing-directory-recovery'])
            # A failed cleanup after publication must not turn a valid snapshot into a failed command.
            fake_git = tools / 'git'
            fake_git.write_text('#!' + sys.executable + '\nimport os,sys\nif "worktree" in sys.argv and "remove" in sys.argv:\n sys.stderr.write("injected cleanup failure\\n");sys.exit(42)\nos.execv(' + repr(real_git) + ',[' + repr(real_git) + ']+sys.argv[1:])\n')
            fake_git.chmod(0o755)
            write('src/main/java/p/A.java', 'package p; public class A { public int value(){return 9;} }')
            published = cli(*common, environ=blocked_env)
            warnings = [json.loads(line) for line in published.stderr.splitlines() if line.startswith('{')]
            assert any(item.get('code') == 'SNAPSHOT_CLEANUP_PENDING' and item['published'] for item in warnings)
            cli('snapshots', 'gc', '--execute')
            assert not (store / 'workspace').exists()
            # Prepare the compatible committed endpoint before injecting target failure.
            cli('index', '.', '--ref', 'HEAD', '--include-tests', '--no-classpath', '--java-version', '25', '--format', 'json')
            write('.anatomist/config.toml', '[scan]\nsource_roots = ["p@MAIN=src/main/java"]\n')
            failed = cli(*common, '--include-tests', environ=blocked_env, code=3)
            errors = [json.loads(line) for line in failed.stderr.splitlines() if line.startswith('{')]
            assert errors[-1]['code'] == 'SNAPSHOT_COVERAGE_MISMATCH', errors
            assert any(item.get('code') == 'SNAPSHOT_CLEANUP_PENDING' and not item['published'] for item in errors)
            (project / '.anatomist/config.toml').unlink()
            cli('snapshots', 'gc', '--execute')
            report['scenarios'].append(mode + ':published-cleanup-warning')
            pin = json.loads(published.stdout)['comparison']['target']['id']
            cli('snapshots', 'pin', pin)
            budget = json.loads(cli('snapshots', 'gc', '--keep', '0', '--max-bytes', '1', '--execute').stdout)
            assert not budget['budget_met'] and (store / 'snapshots' / pin / 'index.db').is_file()
            stats = json.loads(cli('snapshots', 'stats').stdout)
            assert stats['total_bytes'] > 0 and stats['files'].get('workspace', 0) == 0
            report['scenarios'].append(mode + ':budget-and-pin')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    execute(main, 'target/diff-lifecycle-e2e.json')
