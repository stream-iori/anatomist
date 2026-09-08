"""Small real Git/Maven history shared by CLI and Agent version tests."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil


def run(argv, project, env=None):
    result = subprocess.run(list(map(str, argv)), cwd=project, env=env, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError(f"{argv}: exit={result.returncode}\n{result.stdout[-3000:]}\n{result.stderr[-3000:]}")
    return result.stdout.strip()


def create(project, *, maven=False, executor=None):
    execute = executor or run
    project = Path(project).resolve()
    project.mkdir(parents=True, exist_ok=True)
    def write(name, value):
        path = project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
    def git(*args):
        return execute(["git", *args], project)
    def commit():
        git("add", ".")
        git("commit", "-qm", "version fixture")
        return git("rev-parse", "HEAD")
    parent = '<parent><groupId>anatomist.e2e</groupId><artifactId>versions</artifactId><version>1</version></parent>'
    write("pom.xml", '''<project><modelVersion>4.0.0</modelVersion><groupId>anatomist.e2e</groupId>
<artifactId>versions</artifactId><version>1</version><packaging>pom</packaging>
<modules><module>api</module><module>app</module><module>checks</module></modules>
<properties><maven.compiler.release>25</maven.compiler.release></properties>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>''')
    for module, dependency in (("api", ""), ("app", "api"), ("checks", "app")):
        deps = ('<dependencies><dependency><groupId>anatomist.e2e</groupId><artifactId>' + dependency
                + '</artifactId><version>1</version>' + ('<scope>test</scope>' if module == 'checks' else '')
                + '</dependency></dependencies>') if dependency else ''
        write(module + "/pom.xml", '<project><modelVersion>4.0.0</modelVersion>' + parent + '<artifactId>' + module + '</artifactId>' + deps + '</project>')
    write(".gitignore", "target/\n.anatomist/index*\n")
    write(".anatomist/config.toml", '[scan]\nscopes = ["MAIN", "GENERATED"]\n')
    if maven:
        cache = project.parent / "maven-cache"
        seed = os.environ.get("ANATOMIST_E2E_MAVEN_SEED")
        if seed:
            seed_path = Path(seed).resolve()
            if not seed_path.is_dir():
                raise RuntimeError("ANATOMIST_E2E_MAVEN_SEED must be a readable Maven repository")
            shutil.copytree(seed_path, cache, dirs_exist_ok=True, ignore=shutil.ignore_patterns("*.lastUpdated", "*.part", "*.lock"))
        settings = project.parent / "maven-settings.xml"
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>fixture-central</id><url>https://repo.maven.apache.org/maven2</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>')
        # Both index's Maven subprocess and fixture preparation use this owned repository.
        write(".mvn/maven.config", '-Dmaven.repo.local=' + str(project.parent / "maven-cache") + '\n--settings=' + str(settings) + '\n')
    write("AGENTS.md", "Read-only version analysis. Preserve HEAD, branches, staged and disk content. Read ../anatomist-skill.md for the exact skill supplied for this run. Use CLI help for syntax.\n")
    sources = {
        "api/src/main/java/p/I.java": "package p; public interface I { int work(); }",
        "api/src/main/java/p/Tag.java": "package p; public interface Tag {}",
        "api/src/main/java/p/A.java": "package p; public class A { public static int value() { return 1; } public static int oldValue(){return 7;} public static int newValue(){return 8;} }",
        "app/src/main/java/p/Worker.java": "package p; public class Worker implements I { public int work(){return 1;} }",
        "app/src/main/java/p/Caller.java": "package p; public class Caller { public static int call(I i){return i.work();} }",
        "app/src/main/java/p/Direct.java": "package p; public class Direct { public static int call(){return A.value();} }",
        "app/src/main/java/p/Router.java": "package p; public class Router { public int route(){return A.oldValue();} }",
        "app/src/main/java/p/BaseOnly.java": "package p; public class BaseOnly { public int version(){return 1;} }",
        "checks/src/test/java/p/Check.java": "package p; public class Check { public int verify(I i){return Direct.call()+Caller.call(i);} }",
    }
    for index in range(5):
        callee = 'A.value()' if index == 4 else f'C{index+1}.call()'
        sources[f'app/src/main/java/p/C{index}.java'] = f'package p; public class C{index} {{ public static int call(){{return {callee};}} }}'
    for name, value in sources.items():
        write(name, value + '\n')
    git("init", "-q", "-b", "base-tip")
    git("config", "user.name", "Version E2E")
    git("config", "user.email", "version@example.test")
    ancestor = commit()
    git("checkout", "-qb", "feature")
    write("api/src/main/java/p/A.java", sources["api/src/main/java/p/A.java"].replace("return 1", "return 2") + '\n')
    write("app/src/main/java/p/Worker.java", sources["app/src/main/java/p/Worker.java"].replace("implements I", "implements I, Tag").replace("return 1", "return 2") + '\n')
    write("app/src/main/java/p/Router.java", sources["app/src/main/java/p/Router.java"].replace("A.oldValue()", "A.newValue()") + '\n')
    feature = commit()
    git("checkout", "-q", "base-tip")
    write("app/src/main/java/p/BaseOnly.java", sources["app/src/main/java/p/BaseOnly.java"].replace("return 1", "return 99") + '\n')
    base = commit()
    git("checkout", "-q", "feature")
    if maven:
        execute(["mvn", "-q", "install", "-DskipTests"], project)
    return {"refs": git("for-each-ref", "--format=%(refname) %(objectname)", "refs/heads"), "ancestor": ancestor, "feature": feature, "base": base, "head": feature, "status": "",
            "direct_caller": "p.Direct#call()", "test_caller": "p.Check#verify(p.I)",
            "possible_caller": "p.Caller#call(p.I)", "origin": "p.A#value()", "possible_origin": "p.Worker#work()"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--scenario", choices=["work", "tips", "boundary"], required=True)
    args = parser.parse_args()
    facts = create(args.project, maven=True)
    binary = os.environ["ANATOMIST_E2E_BIN"]
    ids = []
    if args.scenario in ("tips", "boundary"):
        for ref in ("base-tip", "feature"):
            captured = json.loads(run([binary, "index", ".", "--ref", ref, "--format", "json"], args.project))
            ids.append(captured["id"])
            if args.scenario == "tips":
                run([binary, "index", ".", "--ref", ref, "--java-version", "17", "--format", "json"], args.project)
    facts.update({"scenario": args.scenario, "fixed_ids": ids if args.scenario == "boundary" else []})
    request = {
        "work": "分析 feature 从 base-tip 分出后的开发影响，包含生产和测试调用方。请用 Anatomist diff 自动准备所需快照，定位直接与可能调用方，并用对应版本的源码验证关键结论。Base 分支后来也有开发，请只归因本分支的工作。",
        "tips": "比较当前 feature 分支与 base-tip 当前端点的调用关系差异，包含生产和测试调用方。请用 Anatomist diff 自动准备所需快照，说明调用目标变化和双方影响，并查看对应版本的源码。已有缓存可能不覆盖测试。",
        "boundary": "仅基于这两个固定快照分析调用影响：base=snapshot:" + (ids[0] if ids else "") + "，target=snapshot:" + (ids[1] if ids else "") + "。不要重新构建快照。请判断这些证据能否证明没有其他生产或测试调用方；说明已经发现的调用方与覆盖限制，并查看对应版本的源码。",
    }[args.scenario]
    request += " 两侧各选一个 diff 返回的变化实体 ID，在其快照中读取源码，保留完整的结构化 diff 与源码证据。临时证据放在 ../evidence/，不修改或复制项目。"
    args.project.parent.joinpath("version-request.md").write_text(request + '\n')
    args.project.parent.joinpath("version-oracle.json").write_text(json.dumps(facts, indent=2))


if __name__ == "__main__":
    main()
