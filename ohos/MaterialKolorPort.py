"""独立两模块 MaterialKolor OHOS producer；仅生成 staging，不发布。"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
from urllib.request import ProxyHandler, build_opener
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
GROUP = "io.github.qsxs.cpf.materialkolor"
TOOL_REVISION = "d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285"
TOOL_DIR = "toolchains/kotlin-native/2.4.20-0.1.0-08"
PAGES = "https://qsxs.github.io/cpf-maven/"
MODULES = ("material-color-utilities", "material-kolor")
TASKS = {"Tasks": ["tasks", "--all"], "Resolve": ["resolveMaterialKolor"],
         "Metadata": ["recordMaterialKolorMetadata"], "NativeX64": ["recordMaterialKolorX64"],
         "NativeArm64": ["recordMaterialKolorArm64"], "Publish": ["publishMaterialKolor"]}
COMPILES = (("Metadata", "metadata", None), ("NativeX64", "native-x64", "ohos_x64"),
            ("NativeArm64", "native-arm64", "ohos_arm64"))
REQUIRED = {"resolveMaterialKolor", "recordMaterialKolorMetadata", "recordMaterialKolorX64",
            "recordMaterialKolorArm64", "publishMaterialKolor", "compileCommonMainWithMetadataCli",
            "compileCommonMainKotlinMetadata", "compileKotlinOhosX64", "compileKotlinOhosArm64"}
POKO_CLASSES = ("com.materialkolor.hct.Hct", "com.materialkolor.palettes.TonalPalette",
                "com.materialkolor.temperature.TemperatureCache", "com.materialkolor.dynamiccolor.ContrastCurve",
                "com.materialkolor.dynamiccolor.ToneDeltaPair", "com.materialkolor.dynamiccolor.DynamicColor")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def record(path):
    path = Path(path)
    data = path.read_bytes()
    return {"path": str(path.resolve()), "size": len(data), "sha256": digest(data)}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def environment():
    return {key: os.environ[key] for key in ("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "SSL_CERT_FILE",
                                              "SSL_CERT_DIR", "LD_LIBRARY_PATH", "GRADLE_USER_HOME", "KONAN_DATA_DIR",
                                              "TMPDIR", "TEMP", "TMP") if key in os.environ}


def checked_tools(work, explicit):
    lock = load(HERE / "tooling-lock.json")
    if lock["revision"] != TOOL_REVISION or lock["repository"] != "https://github.com/qsxs/cpf-port-tooling":
        raise RuntimeError("Immutable shared tooling revision differs")
    if lock["archiveUrl"] != lock["repository"] + "/archive/" + TOOL_REVISION + ".tar.gz":
        raise RuntimeError("Shared tooling archive URL differs")
    if len(lock["files"]) != 20 or len({row["path"] for row in lock["files"]}) != 20:
        raise RuntimeError("Shared tooling file lock differs")
    directory = Path(explicit).resolve() if explicit else (work / "tooling" / TOOL_REVISION).resolve()
    for row in lock["files"]:
        relative = PurePosixPath(row["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in row["path"] or not row["path"].startswith(TOOL_DIR + "/"):
            raise RuntimeError("Unsafe shared tooling file path")
    if not explicit and not all((directory / row["path"]).is_file() for row in lock["files"]):
        with build_opener(ProxyHandler({})).open(lock["archiveUrl"], timeout=60) as response:
            data = response.read(8 * 1024 * 1024 + 1)
        if len(data) > 8 * 1024 * 1024:
            raise RuntimeError("Shared tooling archive is too large")
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            members = archive.getmembers()
            for row in lock["files"]:
                matches = [member for member in members if "/" in member.name and member.name.split("/", 1)[1] == row["path"]]
                if len(matches) != 1 or not matches[0].isfile():
                    raise RuntimeError("Shared tooling archive member differs: " + row["path"])
                stream = archive.extractfile(matches[0])
                if stream is None:
                    raise RuntimeError("Shared tooling archive member is unreadable")
                content = stream.read(row["size"] + 1)
                if len(content) != row["size"] or digest(content) != row["sha256"]:
                    raise RuntimeError("Shared tooling archive bytes differ: " + row["path"])
                target = directory / row["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists() and target.read_bytes() != content:
                    raise RuntimeError("Existing shared tooling bytes differ")
                if not target.exists():
                    with target.open("xb") as out:
                        out.write(content)
    for row in lock["files"]:
        actual = record(directory / row["path"])
        if (actual["size"], actual["sha256"]) != (row["size"], row["sha256"]):
            raise RuntimeError("Shared tooling identity differs: " + row["path"])
    return directory


def source_facts():
    model = load(HERE / "source-inputs.json")
    rows = []
    for module in MODULES:
        item = model["modules"][module]
        root = (HERE / item["sourceRoot"]).resolve()
        expected = {row["member"]: row for row in item["sourceMembers"]}
        count = 42 if module == "material-color-utilities" else 23
        actual = {"commonMain/" + path.relative_to(root).as_posix() for path in root.rglob("*.kt")}
        if len(expected) != count or actual != set(expected):
            raise RuntimeError("Upstream source inventory differs: " + module)
        for key in ("sources", "gmm", "pom", "license"):
            identity = item[key]
            found = record(HERE / identity["path"])
            if (found["size"], found["sha256"]) != (identity["size"], identity["sha256"]):
                raise RuntimeError("Upstream evidence differs: " + module + ":" + key)
        with zipfile.ZipFile(HERE / item["sources"]["path"]) as archive:
            if {name for name in archive.namelist() if name.endswith(".kt")} != set(expected):
                raise RuntimeError("Upstream sources JAR inventory differs: " + module)
            for member, identity in sorted(expected.items()):
                path = root / member.removeprefix("commonMain/")
                data = path.read_bytes()
                if len(data) != identity["size"] or digest(data) != identity["sha256"] or archive.read(member) != data:
                    raise RuntimeError("Upstream source differs: " + member)
                rows.append({"module": module, "member": member, **record(path)})
            if module == "material-color-utilities" and (archive.read("commonMain/com/materialkolor/LICENSE") !=
                    (HERE / item["license"]["path"]).read_bytes() or
                    (root / "com/materialkolor/LICENSE").read_bytes() != (HERE / item["license"]["path"]).read_bytes()):
                raise RuntimeError("MCI embedded license differs")
    root_license = model["rootLicense"]
    actual = record(HERE / root_license["path"])
    if (actual["size"], actual["sha256"]) != (root_license["size"], root_license["sha256"]) or (
            HERE.parent / "LICENSE").read_bytes() != (HERE / root_license["path"]).read_bytes():
        raise RuntimeError("Upstream root license differs")
    return model, rows


def dependency_facts(path):
    lock = load(path)
    template = load(HERE / "dependency-versions.json")
    if lock.get("repository") != PAGES or set(lock.get("dependencies", {})) != set(template["dependencies"]):
        raise RuntimeError("Public dependency lock is incomplete")
    for name, fixed in template["dependencies"].items():
        item = lock["dependencies"][name]
        source, version, root_sha = item.get("sourceCommit", ""), item.get("version", ""), item.get("rootGmmSha256", "")
        if item.get("group") != fixed["group"] or item.get("module") != fixed["module"] or not re.fullmatch(r"[0-9a-f]{40}", source):
            raise RuntimeError("Public dependency identity differs: " + name)
        prefix = re.escape(fixed["baseVersion"]) + r"-cpf11-preview-" + source[:12] + r"-[1-9][0-9]*-[1-9][0-9]*"
        if not re.fullmatch(prefix, version) or not re.fullmatch(r"[0-9a-f]{64}", root_sha):
            raise RuntimeError("Public dependency version or GMM digest differs: " + name)
        artifacts = item.get("artifacts", {})
        if set(artifacts) != {"metadata", "ohos_x64", "ohos_arm64"}:
            raise RuntimeError("Public dependency artifact lock differs: " + name)
        for target, artifact in artifacts.items():
            payload = artifact.get("payload", {})
            if not isinstance(artifact.get("size"), int) or artifact["size"] <= 0 or not re.fullmatch(r"[0-9a-f]{64}", artifact.get("sha256", "")):
                raise RuntimeError("Public dependency artifact digest differs: " + name)
            if "default/manifest" not in payload or not any("/linkdata/" in key and row.get("size", 0) > 0 for key, row in payload.items()):
                raise RuntimeError("Public dependency Klib metadata differs: " + name)
            if target != "metadata" and not any("/ir/" in key and row.get("size", 0) > 0 for key, row in payload.items()):
                raise RuntimeError("Public dependency Native IR differs: " + name)
    return lock


def verify_public_dependencies(lock):
    for name, item in lock["dependencies"].items():
        url = PAGES + item["group"].replace(".", "/") + "/" + item["module"] + "/" + item["version"] + "/" + item["module"] + "-" + item["version"] + ".module"
        with build_opener(ProxyHandler({})).open(url, timeout=60) as response:
            data = response.read(2 * 1024 * 1024 + 1)
            if response.status != 200 or response.geturl() != url or len(data) > 2 * 1024 * 1024:
                raise RuntimeError("Public dependency HTTP response differs: " + name)
        metadata = json.loads(data)
        if digest(data) != item["rootGmmSha256"] or any(metadata["component"].get(key) != item[key] for key in ("group", "module", "version")):
            raise RuntimeError("Public dependency GMM identity differs: " + name)


def identity(args, tools):
    config = load(HERE / "port-config.json")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_sha) or not re.fullmatch(r"[1-9][0-9]*", args.run_id) or not re.fullmatch(r"[1-9][0-9]*", args.run_attempt):
        raise RuntimeError("Immutable source SHA and positive run identifiers are required")
    actual = subprocess.run(["git", "-C", str(HERE.parent), "rev-parse", "HEAD"], env=environment(), check=True, capture_output=True, text=True).stdout.strip()
    if actual != args.source_sha or config["kotlin"] != "2.4.20-0.1.0-08":
        raise RuntimeError("Source checkout or Kotlin version differs")
    model, rows = source_facts()
    lock = dependency_facts(args.dependency_lock)
    recipe = []
    for path in sorted(HERE.rglob("*")):
        relative = path.relative_to(HERE)
        if path.is_file() and not any(part in (".local", ".gradle", ".kotlin", "build", "__pycache__") for part in relative.parts):
            data = path.read_bytes()
            recipe.append({"path": relative.as_posix(), "size": len(data), "sha256": digest(data)})
    version = "5.0.0-cpf11-preview-" + args.source_sha[:12] + "-" + args.run_id + "-" + args.run_attempt
    stable_sources = [{key: row[key] for key in ("module", "member", "size", "sha256")} for row in rows]
    stable = {"sourceCommit": args.source_sha, "sourceTagCommit": model["upstream"]["commit"], "sources": stable_sources,
              "recipe": recipe, "toolingCommit": TOOL_REVISION, "toolingFiles": load(HERE / "tooling-lock.json")["files"],
              "dependencies": lock, "group": GROUP, "version": version}
    fingerprint = digest(json.dumps(stable, sort_keys=True, separators=(",", ":")).encode())
    return {**stable, "sources": rows, "fingerprint": fingerprint, "toolingHome": str(tools),
            "work": str(Path(args.work).resolve()), "repository": str(Path(args.repository).resolve())}


def check_native(home):
    if not home:
        raise RuntimeError("Explicit Kotlin/Native home is required")
    props = dict(line.split("=", 1) for line in (Path(home) / "konan/konan.properties").read_text(encoding="utf-8").splitlines() if "=" in line)
    if props.get("compilerVersion", "").strip() != "2.4.20-0.1.0-08":
        raise RuntimeError("Kotlin/Native compilerVersion differs")


def output_payload(rows, target):
    metadata = ir = False
    targets = set()
    for item in rows:
        path = Path(item["path"])
        if record(path) != item:
            raise RuntimeError("Producer output changed: " + str(path))
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                entries = archive.infolist()
                metadata |= any(("/linkdata/" in entry.filename or entry.filename.endswith(".kotlin_metadata")) and entry.file_size > 0 for entry in entries)
                ir |= any("/ir/" in entry.filename and entry.file_size > 0 for entry in entries)
                if "default/manifest" in archive.namelist():
                    props = dict(line.split("=", 1) for line in archive.read("default/manifest").decode().splitlines() if "=" in line)
                    targets.update(props.get("native_targets", "").split())
        else:
            metadata |= "/linkdata/" in path.as_posix() and item["size"] > 0
            ir |= "/ir/" in path.as_posix() and item["size"] > 0
            if path.as_posix().endswith("/default/manifest"):
                props = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)
                targets.update(props.get("native_targets", "").split())
    if not metadata or (target is not None and (not ir or targets != {target})):
        raise RuntimeError("Producer metadata, IR or target differs")


def verify_stage(value, stage, target):
    row = load(Path(value["work"]) / "evidence" / (stage + ".json"))
    if row["fingerprint"] != value["fingerprint"]:
        raise RuntimeError("Producer stage fingerprint differs: " + stage)
    expected = {module: {(x["path"], x["size"], x["sha256"]) for x in value["sources"] if x["module"] == module} for module in MODULES}
    for module in MODULES:
        suffix = ":compileCommonMainWithMetadataCli" if target is None else (":compileKotlinOhosX64" if target == "ohos_x64" else ":compileKotlinOhosArm64")
        producers = [p for p in row["producers"] if p["task"] == ":" + module + suffix]
        if len(producers) != 1:
            raise RuntimeError("Producer task missing: " + module + stage)
        producer = producers[0]
        public_trace = producer.get("publicDependencyTrace") or {}
        dependency_name = "poko" if module == "material-color-utilities" else "colormath"
        if public_trace.get("dependency") != dependency_name or public_trace.get("target") != (target or "metadata"):
            raise RuntimeError("Compiler public dependency trace differs: " + module + stage)
        if {(x["path"], x["size"], x["sha256"]) for x in producer["sources"]} != expected[module]:
            raise RuntimeError("Compiled source set differs: " + module)
        plugin_hash = "7bdd55014b8e0d8115e99c49d1167c8d85bf1367fdec59ef4af28d0788a114c6" if module == "material-color-utilities" else "4ffe7f38089fa4acdda5d67fa3a858b9fc2fe7ed1f2e58bedbf8d3447c15df39"
        if len(producer["plugins"]) != 1 or producer["plugins"][0]["sha256"] != plugin_hash:
            raise RuntimeError("Compiler plugin identity differs: " + module)
        if target is None:
            cli = producer.get("commonMetadataCli") or {}
            if cli.get("exitCode") != 0 or cli.get("compilerVersion") != "2.4.20-0.1.0-08" or cli.get("retiredTask", {}).get("enabled") is not False:
                raise RuntimeError("Metadata CLI receipt differs: " + module)
        output_payload(producer["outputs"], target)
    return {"stage": stage, "modules": list(MODULES), "target": target, "sourceCount": 65}


def expected_dependencies(value, module):
    deps = {("org.jetbrains.kotlin", "kotlin-stdlib"): "2.4.20-0.1.0-08"}
    external = value["dependencies"]["dependencies"]
    if module == "material-color-utilities":
        item = external["poko"]
        deps[(item["group"], item["module"])] = item["version"]
    else:
        item = external["colormath"]
        deps[(item["group"], item["module"])] = item["version"]
        deps[(GROUP, "material-color-utilities")] = value["version"]
        for group, name in (("org.jetbrains.compose.runtime", "runtime"), ("org.jetbrains.compose.foundation", "foundation"),
                            ("org.jetbrains.compose.ui", "ui"), ("org.jetbrains.compose.material3", "material3")):
            deps[(group, name)] = "1.12.0-0.1.0-11"
    return deps


def inspect_generated(value, native_home):
    work = Path(value["work"])
    repository = Path(value["repository"]) / GROUP.replace(".", "/")
    version = value["version"]
    paths = [("metadata", work / "build/material-color-utilities/metadata-cli/commonMain/klib", None)]
    for target, suffix in (("ohos_x64", "-ohosx64"), ("ohos_arm64", "-ohosarm64")):
        module = "material-color-utilities" + suffix
        paths.append((target, repository / module / version / (module + "-" + version + ".klib"), target))
    proofs = []
    for label, klib, target in paths:
        operations = ("dump-metadata", "dump-metadata-signatures") + (("dump-ir-signatures",) if target else ())
        output = {}
        for operation in operations:
            process = subprocess.run([str(Path(native_home) / "bin/klib"), operation, str(klib)], env=environment(), capture_output=True, text=True)
            if process.returncode:
                raise RuntimeError("Klib " + operation + " failed for " + label + ": " + process.stderr[-4000:])
            output[operation] = process.stdout
            path = work / "evidence" / ("poko-" + label + "-" + operation + ".txt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(process.stdout, encoding="utf-8")
        declared_ir = ""
        if target:
            ir = output["dump-ir-signatures"]
            if "// Declared signatures:" not in ir or "// Imported signatures:" not in ir:
                raise RuntimeError("IR signature sections differ: " + label)
            declared_ir = ir.split("// Declared signatures:", 1)[1].split("// Imported signatures:", 1)[0]
        metadata = output["dump-metadata"].splitlines()
        signatures = output["dump-metadata-signatures"].splitlines()
        for fqn in POKO_CLASSES:
            package, cls = fqn.rsplit(".", 1)
            header = [i for i, line in enumerate(metadata) if re.search(r"\bclass\s+" + re.escape(fqn.replace(".", "/")) + r"\s*:\s*kotlin/Any\s*\{", line)]
            if len(header) != 1:
                raise RuntimeError("Poko class metadata missing: " + fqn + " in " + label)
            block = []
            for line in metadata[header[0] + 1:]:
                if re.fullmatch(r"\s*}\s*", line):
                    break
                block.append(line)
            for method in ("equals", "hashCode", "toString"):
                signature = package + "/" + cls + "." + method + "|" + method + "("
                declarations = [line.strip() for line in block if re.search(r"\bfun\s+" + method + r"\s*\(", line)]
                matches = [line.strip() for line in signatures if signature in line]
                ir_matches = [line.strip() for line in declared_ir.splitlines() if signature in line]
                if len(declarations) != 1 or len(matches) != 1 or (target and len(ir_matches) != 1):
                    raise RuntimeError("Poko own generated declaration missing: " + fqn + "." + method + " in " + label)
                proofs.append({"stage": label, "class": fqn, "method": method, "metadata": declarations[0],
                               "metadataSignature": matches[0], "irSignature": ir_matches[0] if target else None})
    write(work / "evidence/poko-generated.json", {"status": "DECLARATIONS_VERIFIED", "fingerprint": value["fingerprint"],
          "checks": proofs, "plugin": "dev.drewhamilton.poko:poko-compiler-plugin:0.23.2", "link": "NOT_RUN", "runtime": "NOT_RUN"})
    return proofs


def verify_publication(value, native_home):
    repo = Path(value["repository"]) / GROUP.replace(".", "/")
    model = load(HERE / "source-inputs.json")
    config = load(HERE / "port-config.json")
    version = value["version"]
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    files = []
    for module in MODULES:
        expected = expected_dependencies(value, module)
        root_file = repo / module / version / (module + "-" + version + ".module")
        root = load(root_file)
        for suffix, target in (("", None), ("-ohosx64", "ohos_x64"), ("-ohosarm64", "ohos_arm64")):
            name = module + suffix
            directory = repo / name / version
            gmm_file = directory / (name + "-" + version + ".module")
            gmm = load(gmm_file)
            if any(gmm["component"].get(k) != v for k, v in (("group", GROUP), ("module", module), ("version", version))):
                raise RuntimeError("GMM owner differs: " + name)
            if target:
                if (directory / gmm["component"]["url"]).resolve() != root_file.resolve():
                    raise RuntimeError("Native GMM owner URL differs")
                links = [v["available-at"] for v in root["variants"] if v.get("attributes", {}).get("org.jetbrains.kotlin.native.target") == target and "available-at" in v]
                if not links or any(link["group"] != GROUP or link["module"] != name or link["version"] != version or
                                    (root_file.parent / link["url"]).resolve() != gmm_file.resolve() for link in links):
                    raise RuntimeError("GMM available-at differs: " + name)
            api = []
            for variant in gmm["variants"]:
                attrs = variant.get("attributes", {})
                if (not target and attrs.get("org.gradle.usage") == "kotlin-metadata" and attrs.get("org.jetbrains.kotlin.platform.type") == "common") or (target and attrs.get("org.gradle.usage") == "kotlin-api" and attrs.get("org.jetbrains.kotlin.native.target") == target):
                    api.append(variant)
                for dep in variant.get("dependencies", []):
                    ga = (dep["group"], dep["module"])
                    if ga not in expected or dep.get("version", {}).get("requires") != expected[ga]:
                        raise RuntimeError("Unexpected published GMM dependency: " + str(ga))
                for item in variant.get("files", []):
                    path = (directory / item["url"]).resolve()
                    if not path.is_relative_to(directory.resolve()) or record(path)["sha256"] != item["sha256"] or path.stat().st_size != item["size"]:
                        raise RuntimeError("Published GMM artifact differs")
            if not api or any({(d["group"], d["module"]): d["version"]["requires"] for d in v.get("dependencies", [])} != expected or not v.get("files") for v in api):
                raise RuntimeError("Published API dependency set differs: " + name)
            for variant in api:
                output_payload([record(directory / item["url"]) for item in variant["files"]], target)
            pom = ET.fromstring((directory / (name + "-" + version + ".pom")).read_bytes())
            if any(pom.findtext("m:" + key, namespaces=ns) != val for key, val in (("groupId", GROUP), ("artifactId", name), ("version", version))):
                raise RuntimeError("Published POM identity differs: " + name)
            pom_licenses = pom.findall("m:licenses/m:license", ns)
            if len(pom_licenses) != 1 or pom_licenses[0].findtext("m:name", namespaces=ns) != config["modules"][module]["license"] or pom_licenses[0].findtext("m:url", namespaces=ns) != config["modules"][module]["licenseUrl"]:
                raise RuntimeError("Published POM license declaration differs: " + name)
            mapped = {(group, artifact + (suffix if target and (group != "org.jetbrains.kotlin") else "")): ver for (group, artifact), ver in expected.items()}
            pom_deps = {}
            for dep in pom.findall("m:dependencies/m:dependency", ns):
                key = (dep.findtext("m:groupId", namespaces=ns), dep.findtext("m:artifactId", namespaces=ns))
                if dep.findtext("m:scope", namespaces=ns) != ("compile" if target else "runtime"):
                    raise RuntimeError("POM scope differs: " + name)
                pom_deps[key] = dep.findtext("m:version", namespaces=ns)
            if pom_deps != mapped:
                raise RuntimeError("POM dependency set differs: " + name)
            source_jar = directory / (name + "-" + version + "-sources.jar")
            with zipfile.ZipFile(source_jar) as archive:
                expected_src = {x["member"]: x["sha256"] for x in model["modules"][module]["sourceMembers"]}
                actual_src = {entry: digest(archive.read(entry)) for entry in archive.namelist() if entry.endswith(".kt")}
                licenses = [entry for entry in archive.namelist() if entry.endswith("META-INF/licenses/" + module + "/LICENSE")]
                if actual_src != expected_src or len(licenses) != 1 or digest(archive.read(licenses[0])) != model["modules"][module]["license"]["sha256"]:
                    raise RuntimeError("Published sources or module license differ: " + name)
                root_licenses = [entry for entry in archive.namelist() if entry.endswith("META-INF/licenses/upstream-root/LICENSE")]
                provenance_entries = [entry for entry in archive.namelist() if entry.endswith("META-INF/upstream/provenance-" + module + ".json")]
                if len(root_licenses) != 1 or digest(archive.read(root_licenses[0])) != model["rootLicense"]["sha256"]:
                    raise RuntimeError("Published source archive upstream root license differs: " + name)
                if len(provenance_entries) != 1 or archive.read(provenance_entries[0]) != (Path(value["work"]) / ("provenance-" + module + ".json")).read_bytes():
                    raise RuntimeError("Published source archive provenance differs: " + name)
            for key, classifier in (("license", "license.txt"),):
                if digest((directory / (name + "-" + version + "-" + classifier)).read_bytes()) != model["modules"][module][key]["sha256"]:
                    raise RuntimeError("Published license differs")
            if digest((directory / (name + "-" + version + "-upstream-root-license.txt")).read_bytes()) != model["rootLicense"]["sha256"]:
                raise RuntimeError("Published upstream root license differs")
            provenance = directory / (name + "-" + version + "-provenance.json")
            if provenance.read_bytes() != (Path(value["work"]) / ("provenance-" + module + ".json")).read_bytes():
                raise RuntimeError("Published provenance differs")
            if not target:
                for key, classifier in (("sources", "upstream-sources.jar"), ("pom", "upstream-pom.xml"), ("gmm", "upstream-module.json")):
                    if digest((directory / (name + "-" + version + "-" + classifier)).read_bytes()) != model["modules"][module][key]["sha256"]:
                        raise RuntimeError("Published original evidence differs: " + module + ":" + key)
            entries = [path for path in sorted(directory.iterdir()) if path.is_file()]
            if not entries or any(path.stat().st_size <= 0 for path in entries):
                raise RuntimeError("Publication directory is incomplete")
            for path in entries:
                if path.suffix in (".md5", ".sha1", ".sha256", ".sha512"):
                    continue
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    checksum = path.with_name(path.name + "." + algorithm)
                    if not checksum.is_file() or checksum.read_text().strip().lower() != hashlib.new(algorithm, path.read_bytes()).hexdigest():
                        raise RuntimeError("Publication checksum differs: " + str(checksum))
            files.extend(record(path) for path in entries)
    proofs = inspect_generated(value, native_home)
    result = {"status": "VERIFIED_STAGING_NOT_PUBLISHED", "fingerprint": value["fingerprint"], "sourceCommit": value["sourceCommit"],
              "version": version, "group": GROUP, "dependencies": value["dependencies"], "files": files,
              "pokoGeneratedChecks": len(proofs), "compilations": [verify_stage(value, stage, target) for _, stage, target in COMPILES],
              "link": "NOT_RUN", "runtime": "NOT_RUN"}
    write(Path(value["work"]) / "publication.json", result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("Prepare", *TASKS, "Verify"))
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--dependency-lock", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--tooling", type=Path)
    parser.add_argument("--native-home", type=Path)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    tools = checked_tools(work, args.tooling)
    value = identity(args, tools)
    prepared = work / "prepared.json"
    if args.stage == "Prepare":
        if prepared.exists() and load(prepared) != value:
            raise RuntimeError("Existing work identity differs")
        verify_public_dependencies(value["dependencies"])
        write(work / "source-manifest.json", {"fingerprint": value["fingerprint"], "members": value["sources"], "sourceCount": 65})
        model = load(HERE / "source-inputs.json")
        for module in MODULES:
            write(work / ("provenance-" + module + ".json"), {"fingerprint": value["fingerprint"], "sourceCommit": value["sourceCommit"],
                  "upstream": model["upstream"], "group": GROUP, "module": module, "version": value["version"],
                  "sourceMembers": model["modules"][module]["sourceMembers"], "upstreamFiles": model["modules"][module],
                  "rootLicense": model["rootLicense"], "licenseFacts": model["licenseFacts"], "dependencies": value["dependencies"], "toolingCommit": TOOL_REVISION})
        write(prepared, value)
        print(json.dumps({"work": str(work), "toolingHome": str(tools), "fingerprint": value["fingerprint"], "version": value["version"]}))
        return
    if not prepared.is_file() or load(prepared) != value:
        raise RuntimeError("Work identity differs from Prepare")
    check_native(args.native_home)
    if args.stage == "Verify":
        print(json.dumps(verify_publication(value, args.native_home)))
        return
    if args.stage != "Tasks":
        discovery = load(work / "task-discovery.json")
        if discovery["fingerprint"] != value["fingerprint"] or record(discovery["stdout"]["path"]) != discovery["stdout"]:
            raise RuntimeError("Task discovery differs")
    if args.stage not in ("Tasks", "Resolve"):
        if load(work / "evidence/resolve.json")["fingerprint"] != value["fingerprint"]:
            raise RuntimeError("Resolve evidence differs")
        for label, stage, target in COMPILES:
            if label == args.stage:
                break
            verify_stage(value, stage, target)
    if args.stage == "Publish":
        root = Path(value["repository"]) / GROUP.replace(".", "/")
        for module in MODULES:
            for suffix in ("", "-ohosx64", "-ohosarm64"):
                if (root / (module + suffix) / value["version"]).exists():
                    raise RuntimeError("Publication version already exists: " + module + suffix)
    argv = ["sh", str(HERE / "gradlew"), "-p", str(HERE), "--project-cache-dir", str(work / "project-cache"),
            "-PportWork=" + str(work), "-PportTooling=" + str(tools), "-PportFingerprint=" + value["fingerprint"],
            "-PportVersion=" + value["version"], "-PportRepository=" + value["repository"],
            "-PportDependencyLock=" + str(args.dependency_lock.resolve()), "-Pkotlin.project.persistent.dir=" + str(work / "kotlin-cache"),
            "-Pkotlin.native.home=" + str(args.native_home.resolve()), "--console=plain", "--stacktrace", *TASKS[args.stage]]
    logs = work / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    stdout, stderr = logs / (args.stage + ".stdout.log"), logs / (args.stage + ".stderr.log")
    with stdout.open("wb") as out, stderr.open("wb") as err:
        run = subprocess.run(argv, env=environment(), stdout=out, stderr=err)
    if run.returncode:
        print(stderr.read_text(encoding="utf-8", errors="replace")[-16384:], file=sys.stderr)
        raise RuntimeError("Gradle " + args.stage + " failed with exit " + str(run.returncode))
    if args.stage == "Tasks":
        text = stdout.read_text(encoding="utf-8", errors="replace")
        names = {name.lstrip(":") for name in re.findall(r"^\s*([\w:-]+)(?:\s+-|\s*$)", text, re.MULTILINE)}
        if not REQUIRED.issubset(names):
            raise RuntimeError("Required tasks absent from actual discovery")
        write(work / "task-discovery.json", {"fingerprint": value["fingerprint"], "required": sorted(REQUIRED), "stdout": record(stdout)})
    for label, stage, target in COMPILES:
        if label == args.stage:
            verify_stage(value, stage, target)
    print(json.dumps({"stage": args.stage, "exitCode": run.returncode, "fingerprint": value["fingerprint"], "stdout": record(stdout)}))


if __name__ == "__main__":
    main()
