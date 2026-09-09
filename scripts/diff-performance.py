#!/usr/bin/env python3
"""Repeatable cold/warm/changed-file measurements; every repository and store is owned."""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import time
from e2e_report import execute, track, workspace, run_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--files', type=int, nargs='+', default=[1000, 10000])
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--history', type=int, default=100)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--keep-on-failure', action='store_true')
    args = parser.parse_args()
    report = track({'samples': [], 'historical_snapshots': args.history})
    binaries = {'current': args.native.resolve()}
    if args.baseline:
        binaries['baseline'] = args.baseline.resolve()
    for count in args.files:
        for label, binary in binaries.items():
            with workspace('anatomist-diff-perf-') as temporary:
                root = Path(temporary).resolve();repo = root / 'project';repo.mkdir()
                env = dict(os.environ, ANATOMIST_HOME=str(root / 'home'))
                def git(*argv):
                    return run_command(['git', '-C', str(repo), *argv], capture_output=True, text=True, check=True).stdout.strip()
                def cli(argv):
                    started = time.perf_counter()
                    prefix = ['/usr/bin/time', '-l'] if os.uname().sysname == 'Darwin' else ['/usr/bin/time', '-v']
                    result = run_command(prefix + [str(binary)] + argv, cwd=repo, env=env, capture_output=True, text=True, timeout=600)
                    assert result.returncode == 0, result.stderr[-4000:]
                    match = re.search(r'(\d+)\s+maximum resident set size', result.stderr)
                    rss = int(match.group(1)) if match else None
                    return json.loads(result.stdout), round((time.perf_counter() - started) * 1000, 2), rss
                git('init', '-q');git('config', 'user.name', 'Performance Fixture');git('config', 'user.email', 'perf@example.invalid')
                modules = ['m'+str(i) for i in range(5)]
                (repo / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>perf</artifactId><version>1</version><packaging>pom</packaging><modules>' + ''.join('<module>'+m+'</module>' for m in modules) + '</modules></project>')
                (repo / '.gitignore').write_text('target/\n')
                for m in modules:
                    (repo / m).mkdir();(repo / m / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>'+m+'</artifactId><version>1</version></project>')
                files = []
                for i in range(count):
                    file = repo / modules[i % 5] / 'src/main/java/p' / ('C'+str(i)+'.java')
                    file.parent.mkdir(parents=True, exist_ok=True)
                    body = 'return 1;' if i % 20 == 0 else 'return C'+str(i-1)+'.value();'
                    file.write_text('package p; public class C'+str(i)+' { public static int value(){'+body+'} }\n');files.append(file)
                git('add', '.');git('commit', '-qm', 'base');base = git('rev-parse', 'HEAD')
                files[0].write_text(files[0].read_text().replace('return 1', 'return 2'))
                git('add', '.');git('commit', '-qm', 'target')
                common = ['diff', '--base', base, '--target', 'HEAD', '--no-classpath', '--java-version', '25', '--view', 'calls', '--impact', '--format', 'json']
                if label == 'current': common.append('--timings')
                cold, elapsed, rss = cli(common)
                measurements = {'cold': [elapsed], 'warm': [], 'single_change': [], 'batch_change': [], 'ignored_worktree': []}
                max_rss = rss or 0
                for _ in range(args.repeats):
                    document, elapsed, rss = cli(common);measurements['warm'].append(elapsed);max_rss=max(max_rss,rss or 0)
                    if label == 'current':
                        metrics=document['comparison']['timings']['preparation']
                        assert metrics.get('materializations',0)==0 and metrics.get('backups',0)==0 and metrics.get('snapshot_builds',0)==0
                for n in range(args.repeats):
                    files[0].write_text('package p; public class C0 { public static int value(){return '+str(n+3)+';} }\n')
                    git('add', '.');git('commit', '-qm', 'single')
                    _, elapsed, rss = cli(common);measurements['single_change'].append(elapsed);max_rss=max(max_rss,rss or 0)
                for n in range(args.repeats):
                    for file in files[:min(100,count)]:file.write_text(file.read_text()+'// batch '+str(n)+'\n')
                    git('add', '.');git('commit', '-qm', 'batch')
                    _, elapsed, rss=cli(common);measurements['batch_change'].append(elapsed);max_rss=max(max_rss,rss or 0)
                work = list(common);work[work.index('--target')+1]='WORKTREE'
                prior, _, _=cli(work);expected=prior['comparison']['target']['id']
                ignored=repo/'target/report.txt';ignored.parent.mkdir()
                for n in range(args.repeats):
                    ignored.write_text('irrelevant report '+str(n))
                    document,elapsed,rss=cli(work);measurements['ignored_worktree'].append(elapsed);max_rss=max(max_rss,rss or 0)
                    if label=='current':assert document['comparison']['target']['id']==expected
                size_before=sum(p.stat().st_size for p in (root/'home').rglob('*') if p.is_file())
                gc,gc_ms,_=cli(['snapshots','gc','--keep','2','--execute'])
                size_after=sum(p.stat().st_size for p in (root/'home').rglob('*') if p.is_file())
                report['samples'].append({'binary':label,'files':count,'measurements':measurements,'median_ms':{key:statistics.median(values) for key,values in measurements.items()},'max_rss_bytes':max_rss,'storage_before_bytes':size_before,'storage_after_bytes':size_after,'gc_ms':gc_ms})
    # Many real immutable snapshots, using a small fixture so this gate does not manufacture tens of GB.
    if args.history:
        with workspace('anatomist-history-perf-') as temporary:
            root=Path(temporary).resolve();repo=root/'project';repo.mkdir();env=dict(os.environ,ANATOMIST_HOME=str(root/'home'))
            def run(argv):return run_command(argv,cwd=repo,env=env,capture_output=True,text=True,check=True,timeout=120).stdout.strip()
            run(['git','init','-q']);run(['git','config','user.name','History Fixture']);run(['git','config','user.email','history@example.invalid'])
            src=repo/'src/main/java/p/A.java';src.parent.mkdir(parents=True)
            (repo/'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>history</artifactId><version>1</version></project>')
            last=None
            for i in range(args.history):
                src.write_text('package p; public class A { public int value(){return '+str(i)+';} }')
                run(['git','add','.']);run(['git','commit','-qm','history'])
                sha=run(['git','rev-parse','HEAD'])
                last=json.loads(run([str(args.native.resolve()),'index','.', '--ref',sha,'--no-classpath','--java-version','25','--format','json']))
            document=json.loads(run([str(args.native.resolve()),'diff','--base','HEAD','--target','HEAD','--no-classpath','--java-version','25','--timings','--format','json']))
            metrics=document['comparison']['timings']['preparation'];assert metrics.get('snapshot_builds',0)==0
            assert metrics.get('snapshot_validations',0)<=2,metrics
            gc=json.loads(run([str(args.native.resolve()),'snapshots','gc','--keep','2','--execute']))
            assert gc['deleted']==max(0,args.history-2)
            report['history']={'snapshots':args.history,'selection':metrics,'gc_deleted':gc['deleted']}
    if args.baseline:
        regressions=[]
        for count in args.files:
            samples={s['binary']:s for s in report['samples'] if s['files']==count}
            for scene in ('warm','single_change'):
                ratio=samples['current']['median_ms'][scene]/samples['baseline']['median_ms'][scene]
                if ratio>1.1:regressions.append({'files':count,'scene':scene,'ratio':ratio})
        report['regressions_over_10_percent']=regressions
        if regressions:
            raise AssertionError('Warm/single-change regression exceeds 10%: ' + json.dumps(regressions))
    print(json.dumps(report,indent=2))


if __name__=='__main__':execute(main,'target/diff-performance.json')
