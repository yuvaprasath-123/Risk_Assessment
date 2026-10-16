"""Build documentation artifacts only. No application, network or database calls."""
from pathlib import Path
import copy
import hashlib
import json
import re
def bounded_digits(maximum):
    limit=str(maximum);alternatives=[r"[1-9][0-9]{0,"+str(len(limit)-2)+"}"]
    for index,digit in enumerate(limit):
        minimum=1 if index==0 else 0
        if int(digit)>minimum:
            alternatives.append(limit[:index]+"["+str(minimum)+"-"+str(int(digit)-1)+"]"+r"[0-9]{"+str(len(limit)-index-1)+"}")
    return "(?:"+"|".join(alternatives+[limit])+")"

ROOT = Path(__file__).resolve().parents[1]
def write(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
def ref(name): return {"$ref": "#/components/schemas/" + name}
def obj(properties, required=None, **extra):
    return dict(type="object", properties=properties, required=list(properties) if required is None else required,
                additionalProperties=False, **extra)
def arr(items, **kw): return dict(type="array", items=items, **kw)
def text(**kw):
    if kw.get("minLength",0)>0 and "pattern" not in kw:kw["pattern"]=r"\S"
    return dict(type="string", **kw)
def enum(*values): return dict(type="string", enum=list(values))
def integer(lo=0, hi=2147483647): return dict(type="integer", minimum=lo, maximum=hi, **{"x-wire-integer": True})
def nullable(schema): return {"anyOf": [schema, {"type": "null"}]}
BOOL = {"type": "boolean"}
LONG_DIGITS=bounded_digits(9223372036854775807)
ID = text(pattern="^"+LONG_DIGITS+"$", description="Positive decimal string, at most Java Long.MAX_VALUE (9223372036854775807).", **{"x-java-long": True})
REV = text(pattern="^(?:0|"+LONG_DIGITS+")$", description="Nonnegative Java Long as a string.", **{"x-java-long": True})
REV_REQUEST = {"anyOf": [text(pattern="^0*(?:0|"+LONG_DIGITS+")$", **{"x-java-long": True}), integer(0, 9223372036854775807)]}
UTC = text(format="date-time", pattern=r"Z$", description="UTC ISO-8601, up to 9 fractional digits for risk; configuration storage uses 6.")
OFFSET = text(format="date-time", pattern=r"^(?!.*-00:00$)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$", description="Explicit offset, UTC year 1..9999, excluding unknown -00:00; nanoseconds retained.")
HASH = text(pattern="^[0-9a-f]{64}$")
MONEY = text(pattern=r"^(?:0|[1-9][0-9]{0,12})\.[0-9]{2}$", description="Exact NUMBER(15,2) decimal string, never a JSON floating-point number.")
POSITIVE_MONEY = {"allOf":[MONEY,{"not":{"const":"0.00"}}]}
MONEY_REQUEST = text(pattern=r"^0*(?:0|[1-9][0-9]{0,12})(?:\.[0-9]{1,2}0*)?$", description="Plain nonnegative decimal string <=9999999999999.99, exactly representable at scale 2. Leading zeroes and extra trailing fractional zeroes are accepted, matching the service.")
NAMESPACE = text(pattern="^[a-z][a-z0-9.-]{0,63}$")
KEY = text(minLength=1, maxLength=128, description="Trimmed nonblank Unicode text; no controls or unpaired surrogates.")
CODE = text(minLength=1, maxLength=32)
RULES = ["STRUCTURING", "UNUSUAL_FREQUENCY", "UNUSUAL_AMOUNT", "RECENTLY_REACTIVATED_SUPPLIER", "PREVIOUS_RISK_FLAGS", "NEW_SUPPLIER", "NEAR_DUPLICATE_INVOICE"]
STATES = ["ACCEPTED", "IN_PROGRESS", "RETRYABLE_FAILURE", "BLOCKED", "COMPLETED"]
OUTCOMES = ["TRIGGERED", "NOT_TRIGGERED", "DISABLED", "INSUFFICIENT_HISTORY"]
STATUSES = ["DRAFT", "PENDING_VALIDATION", "PENDING_APPROVAL", "APPROVED", "REJECTED", "PROCESSING", "COMPLETE", "FAILED"]
PAIR = obj({"source": NAMESPACE, "key": KEY})
ERROR = obj({"status": integer(400, 599), "code": text(), "message": text(), "timestamp": UTC, "path": text()})

def base(title, url, provider=False):
    return {"openapi": "3.1.0", "info": {"title": title, "version": "2026-10-16", "description":
        ("Developer 2 provider protocol proposal consumed by implemented opt-in clients. This is NOT an existing Developer 1 endpoint. " if provider else "Implemented Developer 2 routes. ") +
        "No credentials are included. See ../README.md and the handoff guide for relational, trust and recovery invariants."},
        "servers": [{"url": url}], "paths": {}, "components": {"securitySchemes": {
            "ManagerBearer": {"type": "http", "scheme": "bearer", "bearerFormat": "User JWT", "description": "Verified user JWT with unprefixed FINANCE_MANAGER. Service credentials are rejected."},
            "ServiceBearer": {"type": "http", "scheme": "bearer", "bearerFormat": "RS256 service JWT", "description": "Separate service trust, issuer, exact receiver audience, kid, expiry, allowlisted subject and receiver-owned permission. No user tokens."}},
            "schemas": {"ApiError": ERROR}}}

manifest = []
def sample(path, value, spec=None, operation=None, direction=None, status=None, schema=None):
    write("examples/" + path + ".json", value)
    if spec:
        manifest.append(dict(file="examples/" + path + ".json", spec=spec, operationId=operation,
                             direction=direction, status=status, schema=schema))
    return value

def operation(spec, method, path, name, response, request=None, success=200, permission=None, description="", errors=(400,401,403,404,409,503)):
    op = {"operationId": name, "summary": name, "description": description or "See handoff contract and response schemas.",
          "security": [{"ServiceBearer" if path.startswith("/internal/") else "ManagerBearer": []}],
          "responses": {str(success): {"description": "Success", "content": {"application/json": {"schema": ref(response)}}}}}
    if permission: op["x-required-permission"] = permission
    parameters = []
    for field in re.findall(r"\{([^}]+)\}", path):
        schema = enum(*RULES) if field == "ruleCode" else enum("LOW","MEDIUM","HIGH") if field == "bandCode" else enum("1","2","3") if field == "approvalLevel" else ID
        parameters.append({"name": field, "in": "path", "required": True, "schema": schema})
    if parameters: op["parameters"] = parameters
    if request: op["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(request)}}}
    for code in errors:
        if code == success: continue
        op["responses"][str(code)] = {"description": {400:"Malformed or out-of-range input",401:"Missing/invalid authentication",403:"Permission/role/source denied",404:"Not found (or internal access disabled)",409:"Known conflict",500:"Sanitized unexpected failure",503:"Unavailable or integrity verification failed"}.get(code,"Failure"), "content": {"application/json": {"schema": ref("ApiError")}}}
    if 401 in errors:
        op["responses"]["401"]["headers"] = {"WWW-Authenticate": {"schema": text(), "description": 'Bearer realm="<receiver>"; invalid/expired credentials additionally carry error="invalid_token". Missing credentials have no error attribute.'}}
    spec["paths"].setdefault(path,{})[method] = op
    return op

CNAME="business-configuration.openapi.json"; RNAME="risk-assessment.openapi.json"
FNAME="facts-provider.openapi.json"; ENAME="evidence-provider.openapi.json"
C=base("Business Configuration", "http://localhost:8086")
cs=C["components"]["schemas"]
version_fields=dict(versionId=ID,versionNumber=integer(1),versionLabel=text(minLength=1,maxLength=100),publicationState=enum("DRAFT","PUBLISHED"),isDemonstration=BOOL,createdAt=UTC,publishedAt=nullable(UTC))
cs["Version"]=obj(version_fields);cs["Versions"]=arr(ref("Version"))
cs["VersionDetails"]=obj(dict(version_fields,description=nullable(text(maxLength=1000)),scorePolicyCode=enum("SUM","CAP_AT_MAX"),maxFinalScore=nullable(integer(1)),rowVersion=REV))
cs["CreateVersion"]=obj(dict(versionLabel=text(minLength=1,maxLength=100),description=nullable(text(maxLength=1000)),isDemonstration=BOOL,scorePolicyCode=enum("SUM","CAP_AT_MAX"),maxFinalScore=nullable(integer(1))),["versionLabel","isDemonstration","scorePolicyCode"], allOf=[{"if":{"properties":{"scorePolicyCode":{"const":"SUM"}}},"then":{"properties":{"maxFinalScore":{"type":"null"}}},"else":{"required":["maxFinalScore"],"properties":{"maxFinalScore":integer(1)}}}])
cs["RiskRule"]=obj(dict(ruleConfigId=ID,versionId=ID,ruleCode=enum(*RULES),displayName=text(minLength=1,maxLength=120),enabled=BOOL,weight=integer(),parameterSchemaVersion=CODE,parameters={"type":"object"},evaluationOrder=integer(1,99999)))
cs["RiskRulePut"]=obj({k:v for k,v in cs["RiskRule"]["properties"].items() if k not in ("ruleConfigId","versionId","ruleCode")}|{"expectedRowVersion":REV_REQUEST})
cs["RiskBand"]=obj(dict(bandConfigId=ID,versionId=ID,bandCode=enum("LOW","MEDIUM","HIGH"),minScoreInclusive=integer(),maxScoreInclusive=nullable(integer()),approvalLevel=integer(1,3)))
cs["RiskBandPut"]=obj(dict(minScoreInclusive=integer(),maxScoreInclusive=nullable(integer()),approvalLevel=integer(1,3),expectedRowVersion=REV_REQUEST),["minScoreInclusive","approvalLevel","expectedRowVersion"])
cs["ApprovalThreshold"]=obj(dict(thresholdConfigId=ID,versionId=ID,approvalLevel=integer(1,3),minAmountInclusive=MONEY,maxAmountExclusive=nullable(MONEY)))
cs["ApprovalThresholdPut"]=obj(dict(minAmountInclusive=MONEY_REQUEST,maxAmountExclusive=nullable(MONEY_REQUEST),expectedRowVersion=REV_REQUEST),["minAmountInclusive","expectedRowVersion"])
cs["ApprovalSla"]=obj(dict(slaConfigId=ID,versionId=ID,approvalLevel=integer(1,3),durationSeconds=integer(1,999999999999),startEventCode=CODE,escalationPolicyCode=CODE,terminalLevelPolicyCode=nullable(CODE)))
cs["ApprovalSlaPut"]=obj({k:v for k,v in cs["ApprovalSla"]["properties"].items() if k not in ("slaConfigId","versionId","approvalLevel")}|{"expectedRowVersion":REV_REQUEST},["durationSeconds","startEventCode","escalationPolicyCode","expectedRowVersion"])
for kind,wrapper in [("RiskRule","rule"),("RiskBand","band"),("ApprovalThreshold","threshold"),("ApprovalSla","sla")]:
    cs[kind+"List"]=arr(ref(kind)); cs["Saved"+kind]=obj({wrapper:ref(kind),"rowVersion":REV})
cs["Readiness"]=obj(dict(versionId=ID,rowVersion=REV,publicationState=enum("DRAFT","PUBLISHED"),isDemonstration=BOOL,structurallyValid=BOOL,ready=BOOL,issues=arr(obj(dict(category=enum("CONFIGURATION_ERROR","POLICY_BLOCKER"),code=text(),path=text(),message=text())))))
cs["PublishRequest"]=obj({"expectedRowVersion":REV_REQUEST})
cs["Published"]=obj(dict(versionId=ID,rowVersion=REV,publicationState={"const":"PUBLISHED"},contentHash=HASH,hashFormat=enum("configuration-content-v2"),publishedAt=UTC,publishedByUserId=ID))
cs["ActivationRequest"]=obj({"expectedPointerRowVersion":nullable(REV_REQUEST)})
policy_props=dict(version={"const":"approval-policy-v1"},currency={"const":"INR"},riskLevels=obj({"LOW":{"const":1},"MEDIUM":{"const":2},"HIGH":{"const":3}}),authority={"const":"MAX_AMOUNT_TIER_AND_RISK_LEVEL"},humanAuthorizationLevels={"const":[1,2,3]},timerStart={"const":"TASK_ENTERS_PENDING"},viewingResetsDeadline={"const":False},ordinaryReassignmentResetsDeadline={"const":False},escalationOrder={"const":[1,2,3]},primaryExpiryActions=obj({"1":{"const":"ESCALATE_TO_L2"},"2":{"const":"ESCALATE_TO_L3"},"3":{"const":"ONE_ELIGIBLE_L3_BACKUP_OR_MANUAL_REVIEW"}}),newTaskDuration={"const":"DESTINATION_LEVEL_CONFIGURED_DURATION"},maximumSameLevelBackups={"const":1},backupEligibility={"const":"DEVELOPER3_CONFIGURED_ELIGIBLE_SAME_LEVEL_BACKUP"},backupCountScope={"const":"APPROVAL_CHAIN"},backupRequiresSameLevelAuthorization={"const":True},absentBackupAction={"const":"MANUAL_REVIEW"},backupExpiryAction={"const":"MANUAL_REVIEW"},newTimerEvents={"const":["FORMAL_ESCALATION_TASK","NEW_BACKUP_TASK"]},developer3AuditEvents={"const":["ESCALATION","EXPIRY","MANUAL_REVIEW","REASSIGNMENT"]},automaticApproval={"const":False},automaticPaymentProcessing={"const":False},slas=arr(obj({k:v for k,v in cs["ApprovalSla"]["properties"].items() if k not in ("slaConfigId","versionId")}),minItems=3,maxItems=3))
cs["ApprovalPolicyV1"]=obj(policy_props)
content_fields=dict(hashFormat=enum("configuration-content-v1","configuration-content-v2"),versionId=ID,versionNumber=integer(1),versionLabel=text(minLength=1,maxLength=100),description=nullable(text(maxLength=1000)),isDemonstration=BOOL,scorePolicyCode=enum("SUM","CAP_AT_MAX"),maxFinalScore=nullable(integer(1)),createdAt=UTC,createdByUserId=ID)
for plural,kind in [("riskRules","RiskRule"),("riskBands","RiskBand"),("approvalThresholds","ApprovalThreshold"),("approvalSlas","ApprovalSla")]:
    content_fields[plural]=arr(obj({k:v for k,v in cs[kind]["properties"].items() if k!="versionId"}))
cs["PublishedContent"]=obj(content_fields|{"approvalPolicy":ref("ApprovalPolicyV1")},list(content_fields),allOf=[{"if":{"properties":{"hashFormat":{"const":"configuration-content-v2"}}},"then":{"required":["approvalPolicy"]},"else":{"not":{"required":["approvalPolicy"]}}}])
published_fields=dict(versionId=ID,versionRowVersion=REV,publishedAt=UTC,publishedByUserId=ID,contentHash=HASH,hashFormat=enum("configuration-content-v1","configuration-content-v2"),configuration=ref("PublishedContent"))
cs["PublishedSnapshot"]=obj(published_fields)
cs["ActiveSnapshot"]=obj(published_fields|dict(pointerRowVersion=REV,activatedAt=UTC,activatedByUserId=ID))
cs["PublicationRejected"]=obj(ERROR["properties"]|{"readiness":ref("Readiness")})
root="/api/v1/configuration";versions=root+"/versions";vp=versions+"/{versionId}"
operation(C,"get",versions,"listVersions","Versions",description="Ordered by VERSION_NUMBER ASC, VERSION_ID ASC; empty list is 200 [].",errors=(401,403,503,500))
operation(C,"post",versions,"createDraftVersion","Version","CreateVersion",201,description="Identity from Oracle; MAX(version_number)+1 allocation, unique constraint conflict => 409 VERSION_ALLOCATION_CONFLICT; JWT actor only.",errors=(400,401,403,409,503,500))
operation(C,"get",vp,"getVersion","VersionDetails",errors=(400,401,403,404,503,500))
for segment,kind,field in [("risk-rules","RiskRule","ruleCode"),("risk-bands","RiskBand","bandCode"),("approval-thresholds","ApprovalThreshold","approvalLevel"),("approval-slas","ApprovalSla","approvalLevel")]:
    operation(C,"get",vp+"/"+segment,"list"+kind+"s",kind+"List",description="Deterministically ordered. Drafts may be incomplete.",errors=(400,401,403,404,503,500))
    operation(C,"put",vp+"/"+segment+"/{"+field+"}","save"+kind,"Saved"+kind,kind+"Put",description="One transaction: lock parent, DRAFT + expected revision, preserve child identity, increment parent revision. No semantic completeness required until publication.",errors=(400,401,403,404,409,503,500))
operation(C,"get",vp+"/publication-readiness","publicationReadiness","Readiness",description="Read-only coherent snapshot. ready only when all seven typed policies, bands, authority mapping, tiers and SLA contracts validate.",errors=(400,401,403,404,503,500))
op=operation(C,"post",vp+"/publish","publishVersion","Published","PublishRequest",description="Real readiness required. Hash complete V2 content including approval-policy-v1. Repeat publication returns 409 VERSION_ALREADY_PUBLISHED; no activation.",errors=(400,401,403,404,409,503,500))
op["responses"]["409"]["content"]["application/json"]["schema"]={"oneOf":[ref("ApiError"),ref("PublicationRejected")]}
operation(C,"post",vp+"/activate","activateVersion","ActiveSnapshot","ActivationRequest",description="Explicit null pointer revision asserts first activation; otherwise expected current pointer revision. Repeat active target with matching revision preserves activation metadata. Published hash verified, competing activations serialized; no content mutation.",errors=(400,401,403,404,409,503,500))
operation(C,"get",root+"/active","activeConfiguration","ActiveSnapshot",description="No pointer: 404 NO_ACTIVE_CONFIGURATION. Fail closed on corrupt published associations/hash.",errors=(401,403,404,503,500))
operation(C,"get","/internal/v1/configuration/active","internalActiveConfiguration","ActiveSnapshot",permission="CONFIGURATION_ACTIVE_READ",errors=(401,403,404,503,500))
operation(C,"get","/internal/v1/configuration/versions/{versionId}","internalPinnedConfiguration","PublishedSnapshot",permission="CONFIGURATION_VERSION_READ",description="Pinned immutable published content; versionRowVersion is the configuration revision, never pointerRowVersion. DRAFT rejected.",errors=(400,401,403,404,409,503,500))

R=base("Risk Assessment", "http://localhost:8087");rs=R["components"]["schemas"]
score=obj(dict(rawScore=integer(0,15032385529),finalScore=integer(),maxFinalScore=integer(1),band=enum("LOW","MEDIUM","HIGH")))
rs["Score"]=score
rs["Factor"]=obj(dict(ruleCode=enum(*RULES),evaluationOrder=integer(1,99999),outcome=enum(*OUTCOMES),configuredWeight=integer(),contribution=integer(),explanation=text()))
rs["Contribution"]=obj({k:v for k,v in rs["Factor"]["properties"].items() if k not in ("evaluationOrder","explanation")})
identity=dict(assessmentId=ID,rowVersion=REV,status=enum(*STATES))
rs["Summary"]=obj(identity|dict(createdAt=UTC,cutoff=UTC,evaluatedAt=nullable(UTC),completedAt=nullable(UTC),configurationVersionId=ID,configurationRowVersion=REV,evaluatorVersion=text(),result=nullable(ref("Score"))))
rs["Breakdown"]=obj(identity|{"result":nullable(obj(score["properties"]|{"contributions":arr(ref("Contribution"),minItems=7,maxItems=7)}))})
rs["Factors"]=obj(identity|{"factors":nullable(arr(ref("Factor"),minItems=7,maxItems=7))})
typed_params={
 "STRUCTURING":obj(dict(windowSeconds=integer(1,3155760000),minimumPayments=integer(2),approvalBoundaryLevels=arr(integer(2,3),minItems=1,maxItems=2,uniqueItems=True),historyEligibility=enum("VALIDATED_SUBMISSIONS","COMPLETED_PAYMENTS"))),
 "UNUSUAL_FREQUENCY":obj(dict(windowSeconds=integer(1,3155760000),maximumSubmissions=integer(1),historyEligibility=enum("VALIDATED_SUBMISSIONS","COMPLETED_PAYMENTS"))),
 "UNUSUAL_AMOUNT":obj(dict(windowSeconds=integer(1,3155760000),minimumObservations=integer(1),meanMultiplier=text(pattern=r"^[0-9]{1,13}(\.[0-9]{1,16})?$"),zeroBaselinePolicy=enum("INSUFFICIENT_HISTORY","COMPARE_TO_ZERO"))),
 "RECENTLY_REACTIVATED_SUPPLIER":obj({"windowSeconds":integer(1,3155760000)}),
 "PREVIOUS_RISK_FLAGS":obj(dict(windowSeconds=integer(1,3155760000),minimumFlaggedPayments=integer(1),qualifyingRuleCodes=arr(enum(*[r for r in RULES if r!="PREVIOUS_RISK_FLAGS"]),minItems=1,maxItems=6,uniqueItems=True))),
 "NEW_SUPPLIER":obj({"windowSeconds":integer(1,3155760000)}),
 "NEAR_DUPLICATE_INVOICE":obj(dict(windowSeconds=integer(1,3155760000),historyEligibility=enum("VALIDATED_SUBMISSIONS","COMPLETED_PAYMENTS"),invoiceNumberNormalization=enum("EXACT","TRIM_UPPERCASE")))}
for code,params in typed_params.items(): cs[code+"ParametersV1"]=params
engine_rule=obj(dict(code=enum(*RULES),enabled=BOOL,weight=integer(),evaluationOrder=integer(1,99999),parameterSchemaVersion={"const":"risk-policy-v1"},parameters={"type":"object"}))
rs["EngineSnapshot"]=obj(dict(versionId=ID,rowVersion=REV,contentHash=HASH,currencyCode={"const":"INR"},maxFinalScore=integer(1),rules=arr(engine_rule,minItems=7,maxItems=7),bands=arr(obj(dict(code=enum("LOW","MEDIUM","HIGH"),minimum=integer(),maximum=nullable(integer()))),minItems=3,maxItems=3),approvalBoundaries=obj({"2":MONEY,"3":MONEY})))
rs["ConfigurationSnapshot"]=obj(identity|dict(snapshotId=ID,configurationHash=HASH,evaluatorVersion=text(),configuration=ref("EngineSnapshot")))
rs["Submission"]=obj(dict(contractVersion={"const":"risk-submission-v1"},submissionSource=NAMESPACE,submissionKey=KEY,paymentReference=ID,validationReference=KEY,validationRevision=nullable(KEY),configurationVersionId=nullable(ID)),["contractVersion","submissionSource","submissionKey","paymentReference","validationReference"])
rs["SubmissionStatus"]=obj(identity|dict(cutoff=UTC,configurationVersionId=ID,configurationRowVersion=REV,completedAt=nullable(UTC),result=nullable(ref("Score")),retryAllowed=BOOL))
for tail,name,schema in [("","assessmentSummary","Summary"),("/score-breakdown","scoreBreakdown","Breakdown"),("/factors","assessmentFactors","Factors"),("/configuration-snapshot","assessmentConfiguration","ConfigurationSnapshot")]:
    operation(R,"get","/api/v1/risk-assessments/{assessmentId}"+tail,name,schema,description="Verified stored reads, never rescoring/writing. Non-completed result/factors are null; snapshot still available after acceptance.",errors=(400,401,403,404,503))
for tail,name,schema,permission in [("","internalAssessment","Summary","RISK_ASSESSMENT_READ"),("/factors","internalFactors","Factors","RISK_FACTORS_READ"),("/configuration-snapshot","internalRiskConfiguration","ConfigurationSnapshot","RISK_CONFIGURATION_READ")]:
    operation(R,"get","/internal/v1/risk-assessments/{assessmentId}"+tail,name,schema,permission=permission,description="COMPLETED only; non-completed => 409 ASSESSMENT_NOT_COMPLETED. No raw evidence/lease/diagnostics.",errors=(400,401,403,404,409,503))
op=operation(R,"post","/internal/v1/risk-assessments","submitAssessment","SubmissionStatus","Submission",permission="RISK_ASSESSMENT_SUBMIT",description="Reference-only, max 16384 Java characters. Namespace must belong to authenticated service. 200 completed; 202 accepted/in-progress/retryable/blocked. Same identity POST drives recovery; completed retry does not refetch or rescore.",errors=(400,401,403,404,409,503))
op["responses"]["202"]={"description":"Accepted status, not a score guarantee", "headers":{"Retry-After":{"schema":text(),"description":"2 when retryAllowed; schedule with backoff, not a lease expiry promise."}},"content":{"application/json":{"schema":ref("SubmissionStatus")}}}
op=operation(R,"get","/internal/v1/risk-assessment-submissions/status","submissionStatus","SubmissionStatus",permission="RISK_ASSESSMENT_SUBMIT",description="READ ONLY. Exactly two query parameters, once each. Does not claim work or retry dependencies. 404 if not accepted. Same-identity POST is the recovery operation.",errors=(400,401,403,404,503))
op["parameters"]=[{"name":name,"in":"query","required":True,"schema":schema} for name,schema in [("submissionSource",NAMESPACE),("submissionKey",KEY)]]

F=base("Authoritative Facts provider protocol (proposal)", "https://facts-provider.invalid",True);fs=F["components"]["schemas"]
fs["FactsRequest"]=obj(dict(contractVersion={"const":"risk-authoritative-facts-v1"},submission=PAIR,paymentId=ID,validationReference=KEY,validationRevision=nullable(KEY),cutoff=OFFSET))
validation=obj(dict(reference=KEY,revision=nullable(KEY),outcome={"const":"PASS"},validatedAt=OFFSET,immutableReference=BOOL),allOf=[{"if":{"properties":{"revision":{"type":"null"}}},"then":{"properties":{"immutableReference":{"const":True}}}}])
fs["FactsResponse"]=obj(dict(contractVersion={"const":"risk-authoritative-facts-v1"},submission=PAIR,paymentId=ID,requesterId=ID,validation=validation,cutoff=OFFSET,
 payment=obj(dict(invoiceId=ID,supplierId=ID,amount=POSITIVE_MONEY,currencyCode={"const":"INR"},submittedAt=OFFSET,completedAt=nullable(OFFSET),statusAsOfCutoff=enum("PENDING_VALIDATION","PENDING_APPROVAL","APPROVED","PROCESSING","COMPLETE")),allOf=[{"if":{"properties":{"statusAsOfCutoff":{"const":"COMPLETE"}}},"then":{"properties":{"completedAt":OFFSET}},"else":{"properties":{"completedAt":{"type":"null"}}}}]),
 invoice=obj(dict(invoiceId=ID,supplierId=ID,invoiceNumber=text(minLength=1,maxLength=120),invoiceAmount=POSITIVE_MONEY,currencyCode={"const":"INR"})),
 supplier=obj(dict(supplierId=ID,statusAsOfCutoff={"const":"ACTIVE"},createdAt=OFFSET)),currentAndRetryIdentities=arr(PAIR,minItems=1,maxItems=1000,uniqueItems=True),
 certification=obj({k:{"const":True} for k in ["complete","asOfCutoff","immutable","validationPaymentBindingVerified","requesterOwnershipVerified","eligibleForRiskAssessment"]}|{"code":{"const":"AUTHORITATIVE_VALIDATION_PASS_V1"},"retryIdentityScope":{"const":"SAME_LOGICAL_SUBMISSION"}}),
 provenance=obj(dict(serviceId=text(),recordReference=KEY,recordRevision=KEY))))
operation(F,"post","/internal/v1/risk-facts/resolve","resolveFacts","FactsResponse","FactsRequest",permission="RISK_FACTS_READ",description="Proposed provider obligation: certify authority via configured provider trust plus PASS/reference/payment binding, not a digest or self-asserted serviceId. Exact request echo, original-cutoff immutable receipt; 410 if irreproducible.",errors=(400,401,403,409,503))
F["paths"]["/internal/v1/risk-facts/resolve"]["post"]["responses"]["410"]={"description":"Original cutoff/immutable reference cannot be reproduced"}

E=base("Upstream Evidence provider protocol (proposal)","https://evidence-provider.invalid",True);es=E["components"]["schemas"]
es["EvidenceScope"]=obj(dict(contractVersion={"const":"risk-upstream-evidence-v1"},submission=PAIR,paymentId=ID,requesterId=ID,validationReference=KEY,validationRevision=nullable(KEY),factsReceipt=obj(dict(serviceId=text(),recordReference=KEY,recordRevision=KEY)),supplierId=ID,currencyCode={"const":"INR"},cutoff=OFFSET,configuration=obj(dict(versionId=ID,rowVersion=REV,configurationHash=HASH)),acceptanceHash=HASH,requiredCoverage=obj(dict(paymentFrom=nullable(OFFSET),transitionFrom=nullable(OFFSET),supplierSnapshotAt=OFFSET,paymentPopulation={"const":"SUBMITTED_OR_COMPLETED_V1"},transitionEnd={"const":"INCLUSIVE"})),excludedIdentities=arr(PAIR,minItems=1,maxItems=1000,uniqueItems=True)))
es["EvidenceRequest"]=obj(dict(request=ref("EvidenceScope"),replayReference=text(pattern="^ue1-[0-9a-f]{64}$"),pageIndex=integer(0,99),pageSize=integer(1,1000),cursor=nullable(text())))
payment=obj(dict(recordReference=KEY,submission=PAIR,paymentId=ID,requesterId=ID,invoiceId=ID,invoiceNumber=text(minLength=1,maxLength=120),supplierId=ID,currencyCode={"const":"INR"},amount=MONEY,submittedAt=OFFSET,completedAt=nullable(OFFSET),statusAsOfCutoff=enum(*STATUSES),validated=BOOL))
supplier=obj(dict(recordReference=KEY,supplierId=ID,createdAt=OFFSET,statusAsOfCutoff={"const":"ACTIVE"}))
transition=obj(dict(recordReference=KEY,supplierId=ID,previousStatus=enum("ACTIVE","INACTIVE"),nextStatus=enum("ACTIVE","INACTIVE"),changedAt=OFFSET))
def section(item): return obj(dict(availability=enum("COMPLETE","INCOMPLETE","UNAVAILABLE"),sourceReference=text(),sourceRevision={"const":"1"},coveredFrom=nullable(OFFSET),records=arr(item)))
es["EvidenceResponse"]=obj(dict(contractVersion={"const":"risk-upstream-evidence-v1"},request=ref("EvidenceScope"),providerServiceId=text(),replayReference=text(pattern="^ue1-[0-9a-f]{64}$"),snapshotRevision={"const":"1"},populationHash=HASH,certification=obj({k:{"const":True} for k in ["immutable","asOfCutoff","supplierWideAcrossRequesters","exclusionsApplied","factsReceiptBindingVerified"]}),pagination=obj(dict(pageIndex=integer(0,99),pageSize=integer(1,1000),totalPages=integer(1,100),totalPayments=integer(0,9000),totalTransitions=integer(0,9000),nextCursor=nullable(text()))),paymentHistory=section(payment),supplier=section(supplier),supplierTransitions=section(transition)))
operation(E,"post","/internal/v1/risk-evidence/resolve","resolveEvidence","EvidenceResponse","EvidenceRequest",permission="RISK_EVIDENCE_READ",description="Proposed provider endpoint. Persist original population before page 0; certification + trusted transport + exact scope + all pages + digest required. Never substitute local prior-risk evidence. [from,cutoff) payment union by submitted OR completed; transitions end inclusive.",errors=(400,401,403,409,503))
E["paths"]["/internal/v1/risk-evidence/resolve"]["post"]["responses"]["410"]={"description":"Persisted replay population/cutoff no longer reproducible: block, never recapture a different population"}

specs={CNAME:C,RNAME:R,FNAME:F,ENAME:E}
for name,spec in specs.items():write("openapi/"+name,spec)

# A complete explicitly labelled demonstration draft. No seeding or API invocation.
rule_parameters=[
 dict(windowSeconds=604800,minimumPayments=2,approvalBoundaryLevels=[2,3],historyEligibility="VALIDATED_SUBMISSIONS"),
 dict(windowSeconds=604800,maximumSubmissions=3,historyEligibility="VALIDATED_SUBMISSIONS"),
 dict(windowSeconds=7776000,minimumObservations=5,meanMultiplier="2",zeroBaselinePolicy="INSUFFICIENT_HISTORY"),
 dict(windowSeconds=2592000),
 dict(windowSeconds=7776000,minimumFlaggedPayments=1,qualifyingRuleCodes=[c for c in RULES if c!="PREVIOUS_RISK_FLAGS"]),
 dict(windowSeconds=2592000),
 dict(windowSeconds=2592000,historyEligibility="VALIDATED_SUBMISSIONS",invoiceNumberNormalization="EXACT")]
create=dict(versionLabel="DEMONSTRATION ONLY - handoff example",description="Synthetic publishable example. INR tier boundaries are demonstration inputs, not approved monetary requirements.",isDemonstration=True,scorePolicyCode="CAP_AT_MAX",maxFinalScore=100)
demo={"create":sample("configuration/create-draft",create,CNAME,"createDraftVersion","request"),"rules":[],"bands":[],"thresholds":[],"slas":[]}
row=0
for index,(code,weight,params) in enumerate(zip(RULES,[35,15,20,15,10,15,25],rule_parameters),1):
    body=dict(displayName=code.replace("_"," ").title(),enabled=True,weight=weight,parameterSchemaVersion="risk-policy-v1",parameters=params,evaluationOrder=index,expectedRowVersion=str(row));row+=1
    sample("configuration/rule-"+code,body,CNAME,"saveRiskRule","request");demo["rules"].append(dict(ruleCode=code,request=body))
for code,lo,hi,level in [("LOW",0,29,1),("MEDIUM",30,60,2),("HIGH",61,100,3)]:
    body=dict(minScoreInclusive=lo,maxScoreInclusive=hi,approvalLevel=level,expectedRowVersion=str(row));row+=1
    sample("configuration/band-"+code,body,CNAME,"saveRiskBand","request");demo["bands"].append(dict(bandCode=code,request=body))
for level,lo,hi in [(1,"0.00","10000.00"),(2,"10000.00","100000.00"),(3,"100000.00",None)]:
    body=dict(minAmountInclusive=lo,maxAmountExclusive=hi,expectedRowVersion=str(row));row+=1
    sample("configuration/threshold-"+str(level),body,CNAME,"saveApprovalThreshold","request");demo["thresholds"].append(dict(approvalLevel=level,request=body))
for level in [1,2,3]:
    body=dict(durationSeconds=86400,startEventCode="APPROVAL_PENDING_V1",escalationPolicyCode="NEXT_LEVEL_V1" if level<3 else "SAME_LEVEL_BACKUP_ONCE_V1",terminalLevelPolicyCode=None if level<3 else "MANUAL_REVIEW_V1",expectedRowVersion=str(row));row+=1
    sample("configuration/sla-"+str(level),body,CNAME,"saveApprovalSla","request");demo["slas"].append(dict(approvalLevel=level,request=body))
demo["publish"]=sample("configuration/publish",{"expectedRowVersion":str(row)},CNAME,"publishVersion","request")
demo["firstActivation"]=sample("configuration/activate-first",{"expectedPointerRowVersion":None},CNAME,"activateVersion","request")
sample("configuration/activate-existing",{"expectedPointerRowVersion":"4"},CNAME,"activateVersion","request")
write("examples/publishable-configuration.json",demo)
sample("configuration/empty-list",[],CNAME,"listVersions","response",200)
v=dict(versionId="1",versionNumber=1,versionLabel=create["versionLabel"],publicationState="DRAFT",isDemonstration=True,createdAt="2026-10-16T00:00:00Z",publishedAt=None)
sample("configuration/created",v,CNAME,"createDraftVersion","response",201)
sample("configuration/details",v|dict(description=create["description"],scorePolicyCode="CAP_AT_MAX",maxFinalScore=100,rowVersion="16"),CNAME,"getVersion","response",200)
sample("configuration/readiness",dict(versionId="1",rowVersion="16",publicationState="DRAFT",isDemonstration=True,structurallyValid=True,ready=True,issues=[]),CNAME,"publicationReadiness","response",200)

submission=dict(contractVersion="risk-submission-v1",submissionSource="payment-request",submissionKey="handoff-demo-001",paymentReference="501",validationReference="validation-501",validationRevision="1")
sample("risk/submission",submission,RNAME,"submitAssessment","request")
sample("risk/retry-omitted-configuration",submission,RNAME,"submitAssessment","request")
sample("risk/retry-explicit-configuration",submission|{"configurationVersionId":"1"},RNAME,"submitAssessment","request")
sample("risk/conflicting-retry",submission|{"paymentReference":"502"},RNAME,"submitAssessment","request")
cutoff="2026-10-16T12:00:00.123456789Z"
accepted=dict(assessmentId="701",rowVersion="0",status="ACCEPTED",cutoff=cutoff,configurationVersionId="1",configurationRowVersion="17",completedAt=None,result=None,retryAllowed=True)
sample("risk/accepted",accepted,RNAME,"submitAssessment","response",202)
sample("risk/in-progress",accepted|dict(rowVersion="1",status="IN_PROGRESS"),RNAME,"submissionStatus","response",200)
sample("risk/retryable-failure",accepted|dict(rowVersion="2",status="RETRYABLE_FAILURE"),RNAME,"submitAssessment","response",202)
sample("risk/blocked",accepted|dict(rowVersion="2",status="BLOCKED",retryAllowed=False),RNAME,"submitAssessment","response",202)
result=dict(rawScore=0,finalScore=0,maxFinalScore=100,band="LOW")
done=accepted|dict(rowVersion="4",status="COMPLETED",completedAt="2026-10-16T12:00:01.123456789Z",result=result,retryAllowed=False)
sample("risk/completed",done,RNAME,"submitAssessment","response",200)
sample("risk/completed-status",done,RNAME,"submissionStatus","response",200)
summary={k:val for k,val in done.items() if k!="retryAllowed"}|dict(createdAt=cutoff,evaluatedAt="2026-10-16T12:00:00.987654321Z",evaluatorVersion="risk-scoring-v1")
sample("risk/summary",summary,RNAME,"assessmentSummary","response",200)
sample("risk/non-completed-summary",{k:val for k,val in accepted.items() if k!="retryAllowed"}|dict(createdAt=cutoff,evaluatedAt=None,evaluatorVersion="risk-scoring-v1"),RNAME,"assessmentSummary","response",200)
factors=[dict(ruleCode=code,evaluationOrder=i,outcome="INSUFFICIENT_HISTORY" if code=="UNUSUAL_AMOUNT" else "NOT_TRIGGERED",configuredWeight=w,contribution=0,explanation="Synthetic contract example: insufficient completed-payment baseline" if code=="UNUSUAL_AMOUNT" else "Synthetic contract example: factor did not trigger") for i,(code,w) in enumerate(zip(RULES,[35,15,20,15,10,15,25]),1)]
sample("risk/factors",dict(assessmentId="701",rowVersion="4",status="COMPLETED",factors=factors),RNAME,"assessmentFactors","response",200)
sample("risk/non-completed-factors",dict(assessmentId="701",rowVersion="0",status="ACCEPTED",factors=None),RNAME,"assessmentFactors","response",200)
sample("risk/breakdown",dict(assessmentId="701",rowVersion="4",status="COMPLETED",result=result|{"contributions":[{k:v for k,v in f.items() if k not in ("evaluationOrder","explanation")} for f in factors]}),RNAME,"scoreBreakdown","response",200)
for name,status,code,msg in [("unauthorized",401,"UNAUTHORIZED","Authentication is required"),("forbidden",403,"FORBIDDEN","Access is denied"),("not-found",404,"SUBMISSION_NOT_FOUND","Risk submission could not be processed"),("conflict",409,"SUBMISSION_CONFLICT","Risk submission could not be processed"),("unavailable",503,"DEPENDENCY_UNAVAILABLE","Risk submission could not be processed"),("bad-request",400,"INVALID_SUBMISSION","A valid request is required")]:
    sample("errors/"+name,dict(status=status,code=code,message=msg,timestamp="2026-10-16T12:00:00Z",path="/internal/v1/risk-assessment-submissions/status" if status==404 else "/internal/v1/risk-assessments"),RNAME,"submissionStatus" if status==404 else "submitAssessment","response",status)
sample("errors/configuration-not-ready",dict(status=409,code="CONFIGURATION_NOT_READY",message="Configuration is not ready for publication",timestamp="2026-10-16T12:00:00Z",path="/api/v1/configuration/versions/1/publish",readiness=dict(versionId="1",rowVersion="0",publicationState="DRAFT",isDemonstration=True,structurallyValid=False,ready=False,issues=[dict(category="CONFIGURATION_ERROR",code="REQUIRED_RULE_MISSING",path="/risk-rules/STRUCTURING",message="Required supported rule entry is missing")])),CNAME,"publishVersion","response",409)

fq=dict(contractVersion="risk-authoritative-facts-v1",submission={"source":"payment-request","key":"handoff-demo-001"},paymentId="501",validationReference="validation-501",validationRevision="1",cutoff=cutoff)
sample("providers/facts-request",fq,FNAME,"resolveFacts","request")
fr=dict(contractVersion=fq["contractVersion"],submission=fq["submission"],paymentId="501",requesterId="601",validation=dict(reference="validation-501",revision="1",outcome="PASS",validatedAt="2026-10-16T11:30:01Z",immutableReference=True),cutoff=cutoff,payment=dict(invoiceId="401",supplierId="301",amount="1234.50",currencyCode="INR",submittedAt="2026-10-16T11:30:00Z",completedAt=None,statusAsOfCutoff="PENDING_APPROVAL"),invoice=dict(invoiceId="401",supplierId="301",invoiceNumber="DEMO-INV-001",invoiceAmount="1234.50",currencyCode="INR"),supplier=dict(supplierId="301",statusAsOfCutoff="ACTIVE",createdAt="2025-01-01T00:00:00Z"),currentAndRetryIdentities=[fq["submission"]],certification=dict(code="AUTHORITATIVE_VALIDATION_PASS_V1",complete=True,asOfCutoff=True,immutable=True,validationPaymentBindingVerified=True,requesterOwnershipVerified=True,eligibleForRiskAssessment=True,retryIdentityScope="SAME_LOGICAL_SUBMISSION"),provenance=dict(serviceId="payment-facts-provider",recordReference="facts-501",recordRevision="1"))
sample("providers/facts-response",fr,FNAME,"resolveFacts","response",200)
# Remaining generated examples (real canonical hashes/snapshots and provider scope) are completed by VerifyExamples.java, then finish_package.py.
write("validation/example-manifest.json",manifest)

def insomnia():
    resources=[{"_id":"wrk_developer2_handoff","_type":"workspace","name":"Developer 2 handoff (local secrets only)","description":"Imported documentation templates. Requests with write methods mutate databases only when you deliberately send them. No auto-run scripts.","scope":"collection"},
       {"_id":"env_developer2_base","_type":"environment","parentId":"wrk_developer2_handoff","name":"Base (blank credentials)","data":{
       "configuration_url":"http://localhost:8086","risk_url":"http://localhost:8087","security_url":"http://localhost:8081",
       "facts_provider_url":"https://facts-provider.invalid","evidence_provider_url":"https://evidence-provider.invalid",
       "manager_token":"","configuration_service_token":"","risk_service_token":"","facts_provider_token":"","evidence_provider_token":"",
       "manager_username":"","manager_password":"","mfa_challenge_id":"","mfa_otp":"","version_id":"","assessment_id":"","expected_row_version":"","expected_pointer_row_version":"","submission_source":"payment-request","submission_key":"handoff-demo-001","payment_reference":"","validation_reference":"","validation_revision":""}},
       {"_id":"env_developer2_manager","_type":"environment","parentId":"env_developer2_base","name":"Manager only - local","data":{"manager_token":"","manager_username":"","manager_password":"","mfa_challenge_id":"","mfa_otp":"","configuration_service_token":"","risk_service_token":"","facts_provider_token":"","evidence_provider_token":""}},
       {"_id":"env_developer2_service","_type":"environment","parentId":"env_developer2_base","name":"Services only - local","data":{"manager_token":"","manager_username":"","manager_password":"","mfa_challenge_id":"","mfa_otp":"","configuration_service_token":"","risk_service_token":"","facts_provider_token":"","evidence_provider_token":""}}]
    for order,name in enumerate(["MFA","Demonstration configuration","Manager","Internal services","Submission recovery","Provider proposals"]):
        resources.append({"_id":"fld_"+name.lower().replace(" ","_"),"_type":"request_group","parentId":"wrk_developer2_handoff","name":name,"environment":{},"metaSortKey":order*1000})
    def req(name,folder,url,method,body=None,token=None):
        value={"_id":"req_"+hashlib.sha256(name.encode()).hexdigest()[:20],"_type":"request","parentId":"fld_"+folder.lower().replace(" ","_"),"name":name,"url":url,"method":method.upper(),"headers":[{"name":"Accept","value":"application/json"}],"parameters":[],"authentication":{},"body":{},"metaSortKey":len(resources)*1000,"settingFollowRedirects":"off","settingSendCookies":False,"settingStoreCookies":False,"description":"Manual request: inspect before sending; never run every folder as an automatic sequence. Refresh returned IDs/revisions locally. Never export filled credentials. No request/response scripts."}
        if body is not None:
            value["body"]={"mimeType":"application/json","text":json.dumps(body,ensure_ascii=False,indent=2)}
            value["headers"].append({"name":"Content-Type","value":"application/json"})
        if token:value["authentication"]={"type":"bearer","token":"{{ _."+token+" }}","prefix":"Bearer","disabled":False}
        resources.append(value)
    for specname,spec in specs.items():
        for path,methods in spec["paths"].items():
            for method,op in methods.items():
                provider=specname in (FNAME,ENAME);internal=path.startswith("/internal/")
                folder="Provider proposals" if provider else "Internal services" if internal else "Manager"
                urlvar="configuration_url" if specname==CNAME else "risk_url" if specname==RNAME else "facts_provider_url" if specname==FNAME else "evidence_provider_url"
                url="{{ _."+urlvar+" }}"+path
                for field in re.findall(r"\{([^}]+)\}",path):
                    url=url.replace("{"+field+"}","{{ _.version_id }}" if field=="versionId" else "{{ _.assessment_id }}" if field=="assessmentId" else "STRUCTURING" if field=="ruleCode" else "LOW" if field=="bandCode" else "1")
                if op["operationId"]=="submissionStatus":url+="?submissionSource={{ _.submission_source }}&submissionKey={{ _.submission_key }}"
                token="facts_provider_token" if specname==FNAME else "evidence_provider_token" if specname==ENAME else "configuration_service_token" if internal and specname==CNAME else "risk_service_token" if internal else "manager_token"
                body=None
                if "requestBody" in op:
                    found=next((m for m in manifest if m["spec"]==specname and m["operationId"]==op["operationId"] and m["direction"]=="request"),None)
                    if found:body=json.loads((ROOT/found["file"]).read_text(encoding="utf-8"))
                    if body and "expectedRowVersion" in body:body["expectedRowVersion"]="{{ _.expected_row_version }}"
                    if body and "expectedPointerRowVersion" in body:body["expectedPointerRowVersion"]=None
                    if op["operationId"]=="submitAssessment":
                        for field,variable in [("submissionSource","submission_source"),("submissionKey","submission_key"),("paymentReference","payment_reference"),("validationReference","validation_reference"),("validationRevision","validation_revision")]:body[field]="{{ _."+variable+" }}"
                req(op["operationId"],folder,url,method,body,token)
    retry=dict(contractVersion="risk-submission-v1",submissionSource="{{ _.submission_source }}",submissionKey="{{ _.submission_key }}",paymentReference="{{ _.payment_reference }}",validationReference="{{ _.validation_reference }}",validationRevision="{{ _.validation_revision }}")
    req("Read-only status before choosing recovery","Submission recovery","{{ _.risk_url }}/internal/v1/risk-assessment-submissions/status?submissionSource={{ _.submission_source }}&submissionKey={{ _.submission_key }}","get",token="risk_service_token")
    req("Same-identity retry (configuration omitted)","Submission recovery","{{ _.risk_url }}/internal/v1/risk-assessments","post",retry,"risk_service_token")
    req("Same-identity retry (explicit pinned version)","Submission recovery","{{ _.risk_url }}/internal/v1/risk-assessments","post",retry|{"configurationVersionId":"{{ _.version_id }}"},"risk_service_token")
    req("Activate existing pointer (refresh revision first)","Manager","{{ _.configuration_url }}/api/v1/configuration/versions/{{ _.version_id }}/activate","post",{"expectedPointerRowVersion":"{{ _.expected_pointer_row_version }}"},"manager_token")
    demo_url="{{ _.configuration_url }}/api/v1/configuration/versions"
    req("Demo 01 create draft (retain ID)","Demonstration configuration",demo_url,"post",demo["create"],"manager_token")
    req("Demo 02 fetch parent revision","Demonstration configuration",demo_url+"/{{ _.version_id }}","get",token="manager_token")
    demo_order=3
    for label,items,segment,codefield in [("Rule",demo["rules"],"risk-rules","ruleCode"),("Band",demo["bands"],"risk-bands","bandCode"),("Threshold",demo["thresholds"],"approval-thresholds","approvalLevel"),("SLA",demo["slas"],"approval-slas","approvalLevel")]:
        for item in items:
            body=copy.deepcopy(item["request"]);body["expectedRowVersion"]="{{ _.expected_row_version }}"
            req(f"Demo {demo_order:02d} "+label+" "+str(item[codefield]),"Demonstration configuration",demo_url+"/{{ _.version_id }}/"+segment+"/"+str(item[codefield]),"put",body,"manager_token");demo_order+=1
    req("Demo 19 readiness must pass","Demonstration configuration",demo_url+"/{{ _.version_id }}/publication-readiness","get",token="manager_token")
    req("Demo 20 publish current revision","Demonstration configuration",demo_url+"/{{ _.version_id }}/publish","post",{"expectedRowVersion":"{{ _.expected_row_version }}"},"manager_token")
    req("Demo 21 inspect active pointer (404 means absent)","Demonstration configuration","{{ _.configuration_url }}/api/v1/configuration/active","get",token="manager_token")
    req("Demo 22A first activation ONLY if pointer absent","Demonstration configuration",demo_url+"/{{ _.version_id }}/activate","post",{"expectedPointerRowVersion":None},"manager_token")
    req("Demo 22B replace pointer ONLY if revision refreshed","Demonstration configuration",demo_url+"/{{ _.version_id }}/activate","post",{"expectedPointerRowVersion":"{{ _.expected_pointer_row_version }}"},"manager_token")
    req("MFA challenge (existing account)","MFA","{{ _.security_url }}/api/v1/auth/login","post",{"username":"{{ _.manager_username }}","password":"{{ _.manager_password }}"})
    req("MFA verify (private local OTP)","MFA","{{ _.security_url }}/api/v1/auth/verify-otp","post",{"challengeId":"{{ _.mfa_challenge_id }}","otp":"{{ _.mfa_otp }}"})
    write("insomnia/developer2.insomnia.json",{"_type":"export","__export_format":4,"__export_date":"2026-10-16T00:00:00.000Z","__export_source":"developer2-code-derived-handoff","resources":resources})

if __name__=="__main__":
    insomnia()
    print("Generated four OpenAPI documents, examples, manifest and secret-free Insomnia v4 structure.")
