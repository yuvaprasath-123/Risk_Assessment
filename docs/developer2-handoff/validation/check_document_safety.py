"""Read-only Git/source checks and redacted document scan; writes only its report."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]; REPO=ROOT.parents[1]
ALLOWED={"business-configuration-service/README.md","risk-assessment-service/README.md","docs/developer2-plan.md","docs/database/developer2-database-design.md"}
def git(*args):return subprocess.check_output(["git",*args],cwd=REPO)
tracked=set(git("diff","--name-only").decode().splitlines())
if not tracked<=ALLOWED:raise SystemExit("Unexpected tracked paths outside authorized documentation; no values displayed")
if git("diff","--cached","--name-only").strip():raise SystemExit("Unexpected staged files; not modified by this check")
files=[REPO/p for p in tracked]+[p for p in ROOT.rglob("*") if p.is_file()]
findings=[];broken=[];protected={}
patterns=[("JWT-shaped value",r"\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b"),
          ("Private signing material",r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
          ("Literal bearer credential",r"Bearer\s+[A-Za-z0-9_-]{30,}")]
def credentials(value,path,file):
    if isinstance(value,dict):
        for key,item in value.items():
            if re.search(r"password|secret|(?:^|_)token$|private.?key",key,re.I) and isinstance(item,str) and item and not item.startswith("{{"):
                findings.append(dict(file=file,path=path+"/"+key,category="Nonblank credential-like JSON value"))
            credentials(item,path+"/"+key,file)
    elif isinstance(value,list):
        for i,item in enumerate(value):credentials(item,path+"/"+str(i),file)
for file in files:
    if file.suffix not in (".json",".md",".py",".java",".txt",".ps1"):raise SystemExit("Unexpected generated/local file in handoff: "+str(file.relative_to(REPO)))
    body=file.read_text(encoding="utf-8");relative=str(file.relative_to(REPO)).replace("\\","/")
    for category,pattern in patterns:
        for match in re.finditer(pattern,body):findings.append(dict(file=relative,line=body[:match.start()].count("\n")+1,category=category))
    if file.suffix==".json":credentials(json.loads(body),"",relative)
    if file.suffix==".md":
        for target in re.findall(r"(?<!!)\[[^\]]+\]\(([^)]+)\)",body):
            if re.match(r"^[a-z]+://",target):continue
            local=target.split("#")[0].strip("<>")
            if local and not (file.parent/local).exists():broken.append(dict(file=relative,target=target))
for name in ["dev2-schema-setup.sql","configuration-schema.sql","risk-schema.sql","developer1-schema-setup.sql"]:
    file=REPO/"docs/database"/name;original=git("show","HEAD:docs/database/"+name)
    if original!=file.read_bytes():raise SystemExit("Protected historical SQL differs: "+name)
    body=file.read_text();assert "EXIT SUCCESS ROLLBACK" in body and "REVIEW-ONLY STOP" in body
    protected[name]=hashlib.sha256(original).hexdigest()
report=dict(changedDocumentsScanned=len(files),redactedSecretFindings=findings,brokenLocalMarkdownLinks=broken,protectedSqlUnchangedSha256=protected,allReviewStopsRetained=True,stagedFiles=[],applicationRuntimeFilesChanged=[],scanLimit="Pattern/credential-field scan, not a formal secret-detection guarantee")
(ROOT/"validation/document-safety-report.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2))
if findings or broken:raise SystemExit(1)
