#!/usr/bin/env python3
"""Exercise real result spilling, a slow reader, concurrent GC and a broken pipe."""
import argparse
import json
import os
from pathlib import Path
import selectors
import subprocess
from e2e_report import execute, track, workspace, run_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--keep-on-failure', action='store_true')
    args = parser.parse_args()
    binary = str(args.native.resolve())
    report = track({'scenarios': []})
    with workspace('anatomist-diff-stress-') as temporary:
        root = Path(temporary).resolve()
        repo = root / 'project'
        repo.mkdir()
        env = dict(os.environ, ANATOMIST_HOME=str(root / 'home'))

        def git(*argv):
            return run_command(['git', '-C', str(repo), *argv], capture_output=True, text=True, check=True).stdout.strip()

        def cli(*argv):
            result = run_command([binary, *argv], cwd=repo, env=env, capture_output=True, text=True, timeout=300)
            assert result.returncode == 0, result.stderr[-4000:]
            return json.loads(result.stdout)

        git('init', '-q')
        git('config', 'user.name', 'Stress Fixture')
        git('config', 'user.email', 'stress@example.invalid')
        (repo / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>stress</artifactId><version>1</version></project>')
        source = repo / 'src/main/java/p'
        source.mkdir(parents=True)
        for value in (0, 1):
            for index in range(100):
                methods = '\n'.join('public int value' + str(i) + '(){return ' + str(value) + ';}' for i in range(150))
                (source / ('C' + str(index) + '.java')).write_text('package p; public class C' + str(index) + ' {\n' + methods + '\n}')
            git('add', '.')
            git('commit', '-qm', 'value ' + str(value))
            if value == 0:
                base = git('rev-parse', 'HEAD')
        document = cli('diff', '--base', base, '--target', 'HEAD', '--no-classpath', '--java-version', '25', '--timings', '--format', 'json')
        assert document['comparison']['timings']['result_spilled_bytes'] > 8 * 1024 * 1024
        assert sum(row['record'] == 'declaration_change' for row in document['changes']) >= 15000
        report['spilled_bytes'] = document['comparison']['timings']['result_spilled_bytes']
        ids = [document['comparison'][side]['id'] for side in ('base', 'target')]
        store = next((root / 'home/versions').iterdir())
        report['scenarios'].append('large-json-spill')
        with (root / 'reader.stderr').open('w+') as errors:
            process = subprocess.Popen([binary, 'diff', '--base', 'snapshot:' + ids[0], '--target', 'snapshot:' + ids[1], '--no-build', '--format', 'ndjson'], cwd=repo, env=env, stdout=subprocess.PIPE, stderr=errors)
            try:
                with selectors.DefaultSelector() as ready:
                    ready.register(process.stdout, selectors.EVENT_READ)
                    assert ready.select(timeout=120), 'reader did not produce a header'
                assert json.loads(process.stdout.readline())['record'] == 'diff_header'
                assert process.poll() is None, 'reader did not block on its large output'
                collected = cli('snapshots', 'gc', '--keep', '0', '--max-age-days', '0', '--execute')
                protected = {item['id'] for item in collected['protected'] if item['reason'] == 'active_reader'}
                assert set(ids) <= protected, collected
                assert all((store / 'snapshots' / identity / 'index.db').is_file() for identity in ids)
                report['scenarios'].append('slow-reader-gc-protection')
                process.stdout.close()
                assert process.wait(timeout=30) != 0
                errors.seek(0)
                assert 'DIFF_OUTPUT_FAILED' in errors.read()
                report['scenarios'].append('broken-pipe-diagnostic')
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=10)
                process.stdout.close()
        assert all(path.name.endswith('.lock') for path in (store / 'results').iterdir())
        collected = cli('snapshots', 'gc', '--keep', '0', '--max-age-days', '0', '--execute')
        assert collected['deleted'] == 2, collected
        assert all(not (store / 'snapshots' / identity / 'index.db').exists() for identity in ids)
        report['scenarios'].append('reader-release-and-spool-cleanup')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    execute(main, 'target/diff-stress-e2e.json')
