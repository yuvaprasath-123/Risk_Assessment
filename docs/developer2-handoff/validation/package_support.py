"""Exact, duplicate-rejecting JSON and file attestations for documentation only."""
from decimal import Decimal
from pathlib import Path
import hashlib
import json

def _pairs(items):
    result={}
    for key,value in items:
        if key in result:raise ValueError("Duplicate JSON property")
        result[key]=value
    return result

def read_json_text(text):
    def invalid_constant(value):raise ValueError("Non-finite JSON number")
    return json.loads(text,parse_float=Decimal,object_pairs_hook=_pairs,parse_constant=invalid_constant)

def read_json(path):return read_json_text(Path(path).read_text(encoding="utf-8"))

def exact_json(value,level=0):
    if value is None:return "null"
    if isinstance(value,bool):return "true" if value else "false"
    if isinstance(value,str):return json.dumps(value,ensure_ascii=False)
    if type(value) is int:return str(value)
    if isinstance(value,Decimal):
        if not value.is_finite():raise ValueError("Non-finite JSON number")
        return format(value,"f")
    if isinstance(value,float):raise ValueError("Binary floating point is not allowed in handoff artifacts")
    indent="  "*(level+1);close="  "*level
    if isinstance(value,list):
        return "[]" if not value else "[\n"+",\n".join(indent+exact_json(item,level+1) for item in value)+"\n"+close+"]"
    if isinstance(value,dict):
        if any(not isinstance(key,str) for key in value):raise ValueError("JSON keys must be strings")
        return "{}" if not value else "{\n"+",\n".join(indent+exact_json(key)+": "+exact_json(item,level+1) for key,item in value.items())+"\n"+close+"}"
    raise ValueError("Unsupported documentation JSON type")

def write_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(exact_json(value)+"\n",encoding="utf-8")

def sha256_file(path):
    """Text-artifact attestation, stable under Git LF/CRLF checkout conversion.

    This is NOT a publication, acceptance, configuration or evidence hash format.
    """
    return hashlib.sha256(Path(path).read_bytes().replace(b"\r\n",b"\n")).hexdigest()

def runtime_fingerprint(repo):
    repo=Path(repo);files=[repo/"developer2-pom.xml"]
    for module in ["risk-rule-contracts","internal-service-security","business-configuration-service","risk-assessment-service"]:
        files.append(repo/module/"pom.xml")
        files.extend(path for path in (repo/module/"src/main").rglob("*") if path.is_file())
    manifest="".join(path.relative_to(repo).as_posix()+":"+sha256_file(path)+"\n" for path in sorted(files,key=lambda p:p.relative_to(repo).as_posix()))
    return hashlib.sha256(manifest.encode("utf-8")).hexdigest()

def verify_attestation(root,report):
    root=Path(root)
    if report.get("attestationFormat")!="sha256-lf-normalized-text-v1":raise AssertionError("Unsupported documentation attestation; rerun VerifyExamples")
    if not report.get("verifiedFiles"):raise AssertionError("Missing current Java example-file attestation; rerun VerifyExamples")
    for name,digest in report["verifiedFiles"].items():
        path=(root/name).resolve()
        if not path.is_relative_to(root.resolve()) or sha256_file(path)!=digest:
            raise AssertionError("Java-verified example changed; rerun VerifyExamples: "+name)
    if report.get("runtimeSourceSha256")!=runtime_fingerprint(root.parents[1]):
        raise AssertionError("Runtime source changed since Java example verification; rebuild classes and rerun VerifyExamples")
