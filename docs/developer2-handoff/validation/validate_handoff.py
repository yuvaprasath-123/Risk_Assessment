"""Offline artifact checks. Requires requirements.txt; never sends application requests."""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.metadata
import json
import re
import sys
sys.dont_write_bytecode=True
from decimal import Decimal
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate as validate_openapi
from package_support import read_json,read_json_text,verify_attestation

ROOT=Path(__file__).resolve().parents[1];REPO=ROOT.parents[1]
def load(path):return read_json(path)
def check(condition,message):
    if not condition:raise AssertionError(message)
def dereference(node,document):
    if isinstance(node,list):return [dereference(n,document) for n in node]
    if not isinstance(node,dict):return node
    if "$ref" in node:
        pointer=node["$ref"];check(pointer.startswith("#/"),"Only local, fully resolvable references expected")
        target=document
        for part in pointer[2:].split("/"):target=target[part.replace("~1","/").replace("~0","~")]
        return dereference(target,document)|{k:dereference(v,document) for k,v in node.items() if k!="$ref"}
    return {k:dereference(v,document) for k,v in node.items()}
def exact(schema,value):
    """Extra wire invariants JSON Schema's mathematical integer type cannot express."""
    if schema.get("x-wire-integer"):check(type(value) is int,"Decimal/floating point token used for exact integer")
    if schema.get("x-java-long") and isinstance(value,str):check(int(value)<=9223372036854775807,"String ID/revision overflows Long")
    if isinstance(value,dict):
        for key,item in value.items():
            if key in schema.get("properties",{}):exact(schema["properties"][key],item)
    if isinstance(value,list) and "items" in schema:
        for item in value:exact(schema["items"],item)
    for branch in schema.get("anyOf",schema.get("oneOf",[])):
        if Draft202012Validator(branch).is_valid(value):exact(branch,value);break

def routes(module):
    result={};base=REPO/module/"src/main/java"
    for file in base.rglob("*Controller.java"):
        source=file.read_text(encoding="utf-8")
        if not re.search(r"@RestController\s",source):continue
        head=source.split("public class",1)[0];prefix=""
        annotation=re.search(r"@RequestMapping\(([^)]*)\)",head)
        if annotation:
            literal=re.search(r'"([^"]*)"',annotation[1])
            if literal:prefix=literal[1]
            else:
                literal=re.search(r'\bPATH\s*=\s*"([^"]*)"',source);check(literal is not None,"Unrecognized route base in "+str(file));prefix=literal[1]
        for match in re.finditer(r"@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?",source):
            literal=re.search(r'"([^"]*)"',match[2] or "");suffix=literal[1] if literal else ""
            if not literal and match[2] and match[2].strip().endswith("PATH"):
                suffix=re.search(r'\bPATH\s*=\s*"([^"]*)"',source)[1]
            result[(match[1].lower(),prefix+suffix)]=str(file.relative_to(REPO)).replace("\\","/")
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--insomnia-schema",required=True);args=parser.parse_args()
    actual_java=load(ROOT/"validation/java-validation-result.json")
    verify_attestation(ROOT,actual_java)
    specs={};counts={};operations={}
    for file in sorted((ROOT/"openapi").glob("*.json")):
        spec=load(file);validate_openapi(spec);dereference(spec,spec)
        for name,schema in spec["components"]["schemas"].items():Draft202012Validator.check_schema(dereference(schema,spec))
        specs[file.name]=spec
        operations[file.name]={op["operationId"]:(method,path,op) for path,item in spec["paths"].items() for method,op in item.items()}
        counts[file.name]=len(operations[file.name])
    examples=load(ROOT/"validation/example-manifest.json")
    covered=set()
    for example in examples:
        spec=specs[example["spec"]];method,path,op=operations[example["spec"]][example["operationId"]]
        schema=op["requestBody"]["content"]["application/json"]["schema"] if example["direction"]=="request" else op["responses"][str(example["status"])]["content"]["application/json"]["schema"]
        schema=dereference(schema,spec);value=load(ROOT/example["file"])
        errors=list(Draft202012Validator(schema,format_checker=FormatChecker()).iter_errors(value))
        check(not errors,example["file"]+": "+"; ".join(e.message for e in errors[:3]));exact(schema,value)
        covered.add((example["spec"],example["operationId"]))
    check(covered=={(name,operation) for name,group in operations.items() for operation in group},"Every operation must have a schema-validated example")
    route_report={}
    for module,name in [("business-configuration-service","business-configuration.openapi.json"),("risk-assessment-service","risk-assessment.openapi.json")]:
        actual=routes(module);declared={(m,p) for m,p,op in operations[name].values()}
        check(set(actual)==declared,"Route mismatch "+module+": missing="+str(set(actual)-declared)+" extra="+str(declared-set(actual)))
        route_report[module]=[{"method":m.upper(),"path":p,"source":source} for (m,p),source in sorted(actual.items())]
    # Provider paths are proposals; check precisely against the implemented client constants.
    for relative,name in [("facts/FactsProviderProtocol.java","facts-provider.openapi.json"),("upstream/EvidenceProviderProtocol.java","evidence-provider.openapi.json")]:
        source=(REPO/"risk-assessment-service/src/main/java/com/ofss/risk"/relative).read_text(encoding="utf-8")
        path=re.search(r'PATH\s*=\s*"([^"]+)"',source)[1]
        check(set(specs[name]["paths"])=={path},"Provider path differs from client")
    v4=load(ROOT/"insomnia/developer2.insomnia.json");v5=load(ROOT/"insomnia/developer2.insomnia-v5.json")
    official=load(args.insomnia_schema);Draft202012Validator.check_schema(official)
    errors=list(Draft202012Validator(official).iter_errors(v5));check(not errors,"Official Insomnia v5.1 schema: "+"; ".join(e.message[:500] for e in errors[:2]))
    check(v4["_type"]=="export" and v4["__export_format"]==4,"Invalid legacy export envelope")
    resources=v4["resources"];ids={r["_id"] for r in resources};check(len(ids)==len(resources),"Duplicate collection IDs")
    environments=[r for r in resources if r["_type"]=="environment"]
    variables={k for e in environments for k in e["data"]}
    for item in resources:
        check(item.get("parentId") is None or item["parentId"] in ids,"Missing resource parent")
        if item["_type"]=="environment":
            for name,value in item["data"].items():
                if re.search("token|password|otp|key|secret",name) and name!="submission_key":check(value=="","Nonblank credential placeholder")
        if item["_type"]=="request":
            serial=json.dumps(item);refs=set(re.findall(r"\{\{ _\.([a-z_]+) \}\}",serial));check(refs<=variables,"Unresolved Insomnia variables "+str(refs-variables))
            if item["body"]:read_json_text(item["body"]["text"])
            if "/api/v1/configuration" in item["url"] or "/api/v1/risk-assessments" in item["url"]:check("manager_token" in json.dumps(item["authentication"]),"Wrong user authentication")
            if "/internal/" in item["url"]:check("manager_token" not in json.dumps(item["authentication"]),"User credential on internal route")
            check(not any(k in item for k in ("preRequestScript","afterResponseScript")),"Unexpected executable collection script")
            url=item["url"]
            expected="facts_provider_token" if url.startswith("{{ _.facts_provider_url }}") else "evidence_provider_token" if url.startswith("{{ _.evidence_provider_url }}") else "configuration_service_token" if url.startswith("{{ _.configuration_url }}/internal/") else "risk_service_token" if url.startswith("{{ _.risk_url }}/internal/") else None if url.startswith("{{ _.security_url }}") else "manager_token"
            check(item["authentication"]==({} if expected is None else {"type":"bearer","token":"{{ _."+expected+" }}","prefix":"Bearer","disabled":False}),"Wrong receiver credential variable")
            check(item.get("settingFollowRedirects")=="off" and item.get("settingSendCookies") is False and item.get("settingStoreCookies") is False,"Unsafe legacy client settings")
            check("metaSortKey" in item,"Missing explicit request order")
    groups=[r for r in resources if r["_type"]=="request_group"]
    check([r["name"] for r in sorted(groups,key=lambda r:r["metaSortKey"])]==["MFA","Demonstration configuration","Manager","Internal services","Submission recovery","Provider proposals"],"Unexpected workflow group order")
    for parent in [r["_id"] for r in groups]:
        siblings=[r for r in resources if r.get("parentId")==parent]
        check(len({r["metaSortKey"] for r in siblings})==len(siblings),"Ambiguous sibling ordering")
    demo=sorted([r for r in resources if r.get("parentId")=="fld_demonstration_configuration"],key=lambda r:r["metaSortKey"])
    check(demo[0]["method"]=="POST" and demo[0]["url"].endswith("/versions") and demo[1]["method"]=="GET","Draft creation/revision must precede child writes")
    check([r["method"] for r in demo[2:18]]==["PUT"]*16,"Exactly sixteen ordered child edits required")
    check([r["method"] for r in demo[18:]]==["GET","POST","GET","POST","POST"],"Readiness/publication/pointer-check/conditional activation order changed")
    check(demo[18]["url"].endswith("/publication-readiness") and demo[19]["url"].endswith("/publish") and demo[20]["url"].endswith("/active"),"Invalid demo workflow routes")
    v5_flat={}
    def flatten(nodes):
        for node in nodes:
            check(node["meta"]["id"] not in v5_flat,"Duplicate native collection IDs")
            v5_flat[node["meta"]["id"]]=node
            if "children" in node:flatten(node["children"])
    flatten(v5["collection"])
    check(set(v5_flat)=={r["_id"] for r in resources if r["_type"] in ("request","request_group")},"Native/legacy collection resources differ")
    for r in resources:
        if r["_id"] not in v5_flat:continue
        node=v5_flat[r["_id"]];check(node["meta"]["sortKey"]==r["metaSortKey"],"Native ordering differs")
        if r["_type"]=="request":
            for field in ["url","method","body","authentication","headers","parameters"]:check(node[field]==r[field],"Native/legacy request differs: "+field)
            check(node["settings"]=={"followRedirects":"off","rebuildPath":True,"encodeUrl":True,"cookies":{"send":False,"store":False}},"Unsafe native client settings")
    check(v5["environments"]["data"]==environments[0]["data"],"Native base environment differs")
    for e in environments[1:]:
        match=next((n for n in v5["environments"]["subEnvironments"] if n["meta"]["id"]==e["_id"]),None)
        check(match is not None and match["data"]==e["data"],"Native auth environment differs")
    request_names={r["name"] for r in resources if r["_type"]=="request"}
    for group in operations.values():check(set(group)<=request_names,"Missing Insomnia operation")
    # Deliberate negative cases demonstrate that validator gates are not just success checks.
    negative=0
    for specname,schema_name,value in [("business-configuration.openapi.json","ApprovalThresholdPut",{"minAmountInclusive":1.25,"expectedRowVersion":"0"}),
       ("business-configuration.openapi.json","CreateVersion",{"versionLabel":"Demo","isDemonstration":True,"scorePolicyCode":"SUM","maxFinalScore":100}),
       ("risk-assessment.openapi.json","Submission",{"contractVersion":"risk-submission-v1","submissionSource":"x","submissionKey":"k","paymentReference":1,"validationReference":"v"}),
       ("facts-provider.openapi.json","FactsResponse",dict(load(ROOT/"examples/providers/facts-response.json"),certification={} ))]:
        schema=dereference(specs[specname]["components"]["schemas"][schema_name],specs[specname]);check(not Draft202012Validator(schema).is_valid(value),"Negative example was accepted");negative+=1
    for value in [Decimal("1.0"),True]:
        try:exact({"x-wire-integer":True},value)
        except AssertionError:negative+=1
        else:raise AssertionError("Wire integer negative accepted")
    check(actual_java["ready"] and actual_java["productionMappingVerified"] and actual_java["evidenceCollectorVerified"],"Missing real-code verification")
    report=dict(specifications=counts,validatedExampleInstances=len(examples),negativeValidationCases=negative,controllerOperations=sum(len(v) for v in route_report.values()),insomniaRequests=len(request_names),insomniaOfficialSchema=official.get("$id"),insomniaOfficialSchemaSha256=hashlib.sha256(Path(args.insomnia_schema).read_bytes()).hexdigest(),insomniaV5SchemaPassed=True,insomniaV4GraphPassed=True,actualInsomniaDesktopImportTested=False,liveApiCalls=False,oracleAccess=False,validators={n:importlib.metadata.version(n) for n in ["openapi-spec-validator","jsonschema","PyYAML"]},javaExampleVerification=actual_java)
    (ROOT/"validation/validation-report.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    (ROOT/"validation/route-coverage.json").write_text(json.dumps(route_report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2))
if __name__=="__main__":main()
