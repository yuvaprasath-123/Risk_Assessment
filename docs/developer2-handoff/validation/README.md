# Offline handoff validation

No Oracle, HTTP requests, application context, service listeners or application-runtime changes are used by these validators. Temporary Python packages and Java compiler output live outside the repository. Artifacts are JSON; PyYAML is installed with the validator requirements but YAML parsing is not counted as an additional executed check.

## Checks performed

- `openapi-spec-validator` 0.9.0: all four OpenAPI 3.1 documents.
- Explicit recursive resolution of every local `$ref`; JSON Schema 2020-12 meta-validation of all component schemas.
- `jsonschema` 4.26.0 + format checks: every example manifest instance against its operation's request or HTTP-status response schema. All 28 documented operations have at least one validated example. Additional wire checks reject decimal/boolean integer tokens and overflowing Java-Long strings.
- Six negative cases: numeric money, SUM with a cap, numeric ID, absent Facts certification, decimal integer token and boolean integer token.
- Code-derived route coverage: exact comparison against all D2 `@RestController` method/base mappings; 17 Configuration + 9 Risk operations. Provider path constants checked independently; provider routes are not attributed to a D1 controller.
- Official Kong Insomnia v5.1 JSON Schema validation; legacy v4 envelope, unique resource IDs, parent references, defined variables, request-body JSON, operation coverage, manager/service credential separation and blank credential values. No request execution or token generation.
- Native/legacy request and environment parity, exact receiver-specific credential variables, explicit MFA/draft/publication/pointer ordering and disabled redirects/cookies in both formats.
- `VerifyExamples.java`: reflection comparison of actual DTO/request record field sets with OpenAPI properties; the actual create/draft/publish/activation parsers, real readiness validator, publication V2 canonical hash, production PublishedConfigurationMapping, Facts decoder and upstream Evidence collector; evaluates the synthetic complete input using the real seven-rule engine. It opens no sockets or datasource and runs no application service. Configuration hashes and generated response projections are written into the example package.
- Java verification fingerprints the runtime source tree, its own verifier source, input fixtures and generated example files. Package validation rejects stale attestations after changes; rebuild application classes and rerun the Java verifier after runtime source changes. This is a local consistency check, not an authenticity/signature guarantee or proof of upstream certification.
- Documentation fingerprints use `sha256-lf-normalized-text-v1` so Git's LF/CRLF conversion does not invalidate the package after checkout. Only physical CRLF line separators normalize; real content changes still fail. This does not change any business publication/acceptance/configuration/evidence hash contract.
- `test_package_tools.py` exercises exact decimal/large-integer/scientific-notation serialization, duplicate/nonfinite rejection, money/Long bounds and stale-verification refusal. The finisher preserves exact JSON numbers instead of decoding them through binary floats. The real empty-draft validator generates the full publication rejection example.
- Final whitespace, changed-path preservation and secret-pattern scan are recorded in the task report. Such scans reduce accidental disclosure risk; they are not a formal guarantee that arbitrary high-entropy text is never sensitive.
- PowerShell's native AST parser checks the fenced PowerShell examples without executing their commands; see [powershell-validation-report.json](powershell-validation-report.json).

The official schema source is [Kong's source-controlled schema](https://raw.githubusercontent.com/Kong/insomnia/develop/schemas/insomnia.schema.5.1.json). Its exact downloaded SHA-256 is in [validation-report.json](validation-report.json). [Kong's import/export reference](https://developer.konghq.com/insomnia/import-export/) describes v5 and legacy v4 imports. **Actual Insomnia desktop import was not performed**; neither schema validation nor resource checks establish UI behavior.

## Reproduce artifact validation

Use Python 3.12 and JDK 21. Dependencies/schema downloads contact package/documentation hosts only, not application APIs. The recorded run installed packages under a temporary target directory; a dedicated temporary virtual environment is equivalent:

```powershell
$handoffTools = Join-Path $env:TEMP 'developer2-handoff-validators'
python -m pip install --target $handoffTools -r .\docs\developer2-handoff\validation\requirements.txt
$env:PYTHONPATH = $handoffTools
$handoffSchema = Join-Path $handoffTools 'insomnia.schema.5.1.json'
# Uses normal TLS verification; do not disable certificate checks:
python -c "import urllib.request,sys; open(sys.argv[1],'wb').write(urllib.request.urlopen('https://raw.githubusercontent.com/Kong/insomnia/develop/schemas/insomnia.schema.5.1.json',timeout=20).read())" $handoffSchema
python .\docs\developer2-handoff\validation\validate_handoff.py --insomnia-schema $handoffSchema
python .\docs\developer2-handoff\validation\test_package_tools.py
.\docs\developer2-handoff\validation\validate_powershell.ps1
python .\docs\developer2-handoff\validation\check_document_safety.py
```

This checks the committed/generated examples as they stand. The validator exits nonzero on any failure. It writes `validation-report.json` and `route-coverage.json`; it does not rewrite runtime files. JSON Schema does not replace domain/hash/provider certification checks.

## Rebuild examples against application classes

Regenerate only intentionally, because the base generator resets examples before the Java pass completes them:

```powershell
# First build/install shared and service artifacts; no tests/services/Oracle:
mvn -f developer2-pom.xml '-DskipTests' install
$handoffCpFile = Join-Path $env:TEMP 'developer2-handoff-classpath.txt'
mvn -f risk-assessment-service/pom.xml dependency:build-classpath "-Dmdep.outputFile=$handoffCpFile" '-DincludeScope=test'
$handoffClasses = Join-Path $env:TEMP 'developer2-handoff-java'
New-Item -ItemType Directory -Force -Path $handoffClasses | Out-Null
$handoffClasspath = "business-configuration-service\target\classes;risk-assessment-service\target\classes;" + (Get-Content -Raw $handoffCpFile).Trim()
python .\docs\developer2-handoff\validation\build_package.py
javac -encoding UTF-8 -cp $handoffClasspath -d $handoffClasses .\docs\developer2-handoff\validation\VerifyExamples.java
if ($LASTEXITCODE -ne 0) { throw 'Documentation Java validation did not compile' }
java -cp "$handoffClasses;$handoffClasspath" VerifyExamples "$PWD\docs\developer2-handoff"
if ($LASTEXITCODE -ne 0) { throw 'Documentation examples failed application validation' }
python .\docs\developer2-handoff\validation\finish_package.py
python .\docs\developer2-handoff\validation\validate_handoff.py --insomnia-schema $handoffSchema
```

During this handoff the Java verifier used the existing risk Surefire report's `java.class.path` and existing compiled classes (no Maven reactor/test rerun). Its output directory was temporary. The initial compiler invocation needed permission to read cached Maven libraries; the successful invocation was offline. Unchecked casts in the documentation helper produce compiler notes; no runtime application code was modified. Early generator/schema-check failures were corrected before the final successful validation report.
