"""Finish artifacts after VerifyExamples has used the actual application parsers/hashes."""
from pathlib import Path
import json
import sys
sys.dont_write_bytecode=True
from package_support import read_json,write_json,exact_json

ROOT=Path(__file__).resolve().parents[1]
def load(path):return read_json(ROOT/path)
def write(path,value):
    write_json(ROOT/path,value)

# Attach actual-code generated projections to the independently schema-validated manifest.
manifest=load("validation/example-manifest.json")
config="business-configuration.openapi.json";risk="risk-assessment.openapi.json"
for path,operation in [("configuration/pinned-snapshot","internalPinnedConfiguration"),("configuration/active-snapshot","activeConfiguration"),("configuration/published","publishVersion"),("configuration/rules","listRiskRules"),("configuration/bands","listRiskBands"),("configuration/thresholds","listApprovalThresholds"),("configuration/slas","listApprovalSlas"),("configuration/saved-rule","saveRiskRule"),("configuration/saved-band","saveRiskBand"),("configuration/saved-threshold","saveApprovalThreshold"),("configuration/saved-sla","saveApprovalSla"),("risk/configuration-snapshot","assessmentConfiguration"),("providers/evidence-response","resolveEvidence")]:
    spec=config if path.startswith("configuration/") else risk if path.startswith("risk/") else "evidence-provider.openapi.json"
    manifest.append(dict(file="examples/"+path+".json",spec=spec,operationId=operation,direction="response",status=200,schema=None))
manifest.append(dict(file="examples/providers/evidence-request.json",spec="evidence-provider.openapi.json",operationId="resolveEvidence",direction="request",status=None,schema=None))
for path,spec,operation in [("configuration/active-snapshot",config,"activateVersion"),("configuration/active-snapshot",config,"internalActiveConfiguration"),("risk/summary",risk,"internalAssessment"),("risk/factors",risk,"internalFactors"),("risk/configuration-snapshot",risk,"internalRiskConfiguration")]:
    manifest.append(dict(file="examples/"+path+".json",spec=spec,operationId=operation,direction="response",status=200,schema=None))
manifest=list({(m["file"],m["spec"],m["operationId"],m["direction"],m["status"]):m for m in manifest}.values())
write("validation/example-manifest.json",manifest)

# Embed the validated wire examples too, for clients that import just the OpenAPI file.
specs={name:load("openapi/"+name) for name in {m["spec"] for m in manifest}}
for example in manifest:
    spec=specs[example["spec"]]
    operation=next(op for item in spec["paths"].values() for op in item.values() if op["operationId"]==example["operationId"])
    content=operation["requestBody"]["content"]["application/json"] if example["direction"]=="request" else operation["responses"][str(example["status"])]["content"]["application/json"]
    label=Path(example["file"]).stem
    content.setdefault("examples",{})[label]={"summary":"Synthetic validated handoff example: "+label,"value":load(example["file"])}
for name,spec in specs.items():write("openapi/"+name,spec)

# Rebuild only the collection with complete provider requests; do not rerun the base generator.
v4=load("insomnia/developer2.insomnia.json")
for resource in v4["resources"]:
    if resource.get("_type")=="request" and resource["name"]=="resolveEvidence":
        resource["body"]={"mimeType":"application/json","text":exact_json(load("examples/providers/evidence-request.json"))}
        if not any(header["name"].lower()=="content-type" for header in resource["headers"]):resource["headers"].append({"name":"Content-Type","value":"application/json"})
write("insomnia/developer2.insomnia.json",v4)
resources=v4["resources"]; workspace=next(r for r in resources if r["_type"]=="workspace")
base=next(r for r in resources if r["_type"]=="environment" and r["parentId"]==workspace["_id"])
def children(parent):
    result=[]
    for resource in resources:
        if resource.get("parentId")!=parent:continue
        if resource["_type"]=="request_group":
            result.append(dict(name=resource["name"],meta={"id":resource["_id"],"sortKey":resource["metaSortKey"]},children=children(resource["_id"])))
        if resource["_type"]=="request":
            result.append({k:v for k,v in resource.items() if k in ("name","url","method","headers","parameters","body","authentication")}|{"meta":{"id":resource["_id"],"description":resource["description"],"sortKey":resource["metaSortKey"]},"settings":{"followRedirects":"off","rebuildPath":True,"encodeUrl":True,"cookies":{"send":False,"store":False}}})
    return result
v5=dict(type="collection.insomnia.rest/5.0",schema_version="5.1",name=workspace["name"],meta={"id":workspace["_id"],"isPrivate":True,"description":workspace["description"]},collection=children(workspace["_id"]),environments={"name":base["name"],"meta":{"id":base["_id"]},"data":base["data"],"subEnvironments":[{"name":r["name"],"meta":{"id":r["_id"],"isPrivate":True},"data":r["data"]} for r in resources if r.get("parentId")==base["_id"]]})
write("insomnia/developer2.insomnia-v5.json",v5)
print("Linked actual-code examples and created native v5 collection (plus legacy v4 alternative).")
