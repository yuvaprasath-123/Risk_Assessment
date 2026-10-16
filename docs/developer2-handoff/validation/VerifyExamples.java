import java.nio.file.*;
import java.time.*;
import java.math.*;
import java.util.*;
import java.security.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import com.ofss.configuration.version.*;
import com.ofss.configuration.rule.*;
import com.ofss.configuration.band.*;
import com.ofss.configuration.threshold.*;
import com.ofss.configuration.sla.*;
import com.ofss.configuration.readiness.*;
import com.ofss.configuration.publication.*;
import com.ofss.configuration.activation.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.persistence.*;
import com.ofss.risk.facts.*;
import com.ofss.risk.upstream.*;
import com.ofss.risk.engine.*;

/** Offline documentation verification. Never constructs a datasource, HTTP client or Spring context. */
public class VerifyExamples {
    static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static Path root;
    static final Map<String,String> VERIFIED_FILES=new TreeMap<>();
    static Map<String,Object> read(String path) throws Exception{return JSON.readValue(Files.readString(root.resolve(path)),Map.class);}
    static void write(String path,Object value) throws Exception {var p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n");if(path.startsWith("examples/"))VERIFIED_FILES.put(path,fileHash(p));}
    // Documentation/source attestation only: Git may convert LF/CRLF on checkout.
    // Publication/engine/acceptance/evidence canonical hashes remain unchanged.
    static String fileHash(Path file)throws Exception{
        byte[] bytes=Files.readAllBytes(file);var normalized=new java.io.ByteArrayOutputStream();
        for(int i=0;i<bytes.length;i++){if(bytes[i]=='\r'&&i+1<bytes.length&&bytes[i+1]=='\n')continue;normalized.write(bytes[i]);}
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()));
    }
    static String runtimeFingerprint()throws Exception {
        Path repo=root.getParent().getParent();var files=new ArrayList<Path>();files.add(repo.resolve("developer2-pom.xml"));
        for(String module:List.of("risk-rule-contracts","internal-service-security","business-configuration-service","risk-assessment-service")){
            files.add(repo.resolve(module+"/pom.xml"));try(var paths=Files.walk(repo.resolve(module+"/src/main"))){files.addAll(paths.filter(Files::isRegularFile).toList());}
        }
        files.sort(Comparator.comparing(p->repo.relativize(p).toString().replace('\\','/')));var manifest=new StringBuilder();
        for(var file:files)manifest.append(repo.relativize(file).toString().replace('\\','/')).append(':').append(fileHash(file)).append('\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static long num(Object value){return new BigInteger(value.toString()).longValueExact();}
    static int number(Object value){return Math.toIntExact(num(value));}
    static Map<String,Object> sorted(Object... pairs){var m=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static Object deepSort(Object v){if(v instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,w)->out.put((String)k,deepSort(w)));return out;}if(v instanceof List<?> l)return l.stream().map(VerifyExamples::deepSort).toList();return v;}
    static String digest(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(deepSort(value))));}
    static int verifyFields(String spec,Object[][] entries)throws Exception {
        var schemas=(Map<String,Object>)((Map<?,?>)read("openapi/"+spec).get("components")).get("schemas");
        for(var entry:entries){
            String name=(String)entry[0];Class<?> type=(Class<?>)entry[1];
            var actual=Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
            var properties=(Map<?,?>)((Map<?,?>)schemas.get(name)).get("properties");
            if(!actual.equals(properties.keySet()))throw new AssertionError("OpenAPI fields differ from actual record: "+name);
        }
        return entries.length;
    }
    public static void main(String[]args)throws Exception{
        root=Path.of(args[0]);
        for(String file:List.of("validation/VerifyExamples.java","examples/publishable-configuration.json","examples/providers/facts-response.json","examples/risk/submission.json"))VERIFIED_FILES.put(file,fileHash(root.resolve(file)));
        int checkedFields=verifyFields("business-configuration.openapi.json",new Object[][]{
            {"Version",ConfigurationVersionResponse.class},{"VersionDetails",ConfigurationVersionDetails.Response.class},
            {"CreateVersion",CreateConfigurationVersionRequest.class},{"RiskRule",RiskRule.Response.class},{"RiskRulePut",DraftRiskRuleRequest.class},
            {"RiskBand",RiskBand.Response.class},{"RiskBandPut",DraftRiskBandRequest.class},{"ApprovalThreshold",ApprovalThreshold.Response.class},
            {"ApprovalThresholdPut",DraftApprovalThresholdRequest.class},{"ApprovalSla",ApprovalSla.Response.class},{"ApprovalSlaPut",DraftApprovalSlaRequest.class},
            {"SavedRiskRule",DraftRiskRuleService.SavedRule.class},{"SavedRiskBand",DraftRiskBandService.SavedBand.class},
            {"SavedApprovalThreshold",DraftApprovalThresholdService.SavedThreshold.class},{"SavedApprovalSla",DraftApprovalSlaService.SavedSla.class},
            {"Readiness",PublicationReadiness.class},{"PublishRequest",PublishConfigurationRequest.class},
            {"Published",ConfigurationPublicationService.PublishedVersion.class},{"ActivationRequest",ActivationRequest.class},
            {"ActiveSnapshot",ConfigurationActivationService.ActiveSnapshot.class},{"PublishedSnapshot",ConfigurationActivationService.PublishedSnapshot.class},
            {"ApiError",com.ofss.configuration.error.ApiError.class}});
        checkedFields+=verifyFields("risk-assessment.openapi.json",new Object[][]{
            {"Summary",com.ofss.risk.http.AssessmentReadController.Summary.class},{"Breakdown",com.ofss.risk.http.AssessmentReadController.Breakdown.class},
            {"Contribution",com.ofss.risk.http.AssessmentReadController.Contribution.class},{"Factors",com.ofss.risk.http.AssessmentReadController.Factors.class},
            {"ConfigurationSnapshot",com.ofss.risk.http.AssessmentReadController.ConfigurationSnapshot.class},{"Factor",AssessmentReadView.Factor.class},
            {"Score",AssessmentReadView.Score.class},{"Submission",SubmissionCommand.class},{"SubmissionStatus",RiskSubmissionService.Status.class},
            {"EngineSnapshot",RiskConfiguration.class},{"ApiError",com.ofss.risk.http.error.ApiError.class}});
        var demo=read("examples/publishable-configuration.json");
        var create=CreateConfigurationVersionRequest.from((Map)demo.get("create"));
        var parent=new ConfigurationVersionDetails(new ConfigurationVersion(1,1,create.versionLabel(),"DRAFT",true,
            OffsetDateTime.parse("2026-10-16T00:00:00Z"),null),create.description(),create.scorePolicyCode(),create.maxFinalScore(),16);
        var rules=new ArrayList<RiskRule>();var bands=new ArrayList<RiskBand>();var thresholds=new ArrayList<ApprovalThreshold>();var slas=new ArrayList<ApprovalSla>();
        long revision=0;
        for(var item:(List<Map<String,Object>>)demo.get("rules")){
            var code=(String)item.get("ruleCode");DraftRiskRuleRequest.validateRuleCode(code);var r=DraftRiskRuleRequest.from((Map)item.get("request"));
            if(r.expectedRowVersion()!=revision++)throw new AssertionError("Rule revision");
            rules.add(new RiskRule(100+revision,1,code,r.displayName(),r.enabled(),r.weight(),r.parameterSchemaVersion(),r.parameters(),r.evaluationOrder()));
        }
        for(var item:(List<Map<String,Object>>)demo.get("bands")){
            var code=(String)item.get("bandCode");DraftRiskBandRequest.validateCode(code);var r=DraftRiskBandRequest.from((Map)item.get("request"));
            if(r.expectedRowVersion()!=revision++)throw new AssertionError("Band revision");
            bands.add(new RiskBand(200+revision,1,code,r.minScoreInclusive(),r.maxScoreInclusive(),r.approvalLevel()));
        }
        for(var item:(List<Map<String,Object>>)demo.get("thresholds")){
            var level=DraftApprovalThresholdRequest.approvalLevel(item.get("approvalLevel").toString());var r=DraftApprovalThresholdRequest.from((Map)item.get("request"));
            if(r.expectedRowVersion()!=revision++)throw new AssertionError("Threshold revision");
            thresholds.add(new ApprovalThreshold(300+revision,1,level,r.minAmountInclusive(),r.maxAmountExclusive()));
        }
        for(var item:(List<Map<String,Object>>)demo.get("slas")){
            var level=DraftApprovalSlaRequest.approvalLevel(item.get("approvalLevel").toString());var r=DraftApprovalSlaRequest.from((Map)item.get("request"));
            if(r.expectedRowVersion()!=revision++)throw new AssertionError("SLA revision");
            slas.add(new ApprovalSla(400+revision,1,level,r.durationSeconds(),r.startEventCode(),r.escalationPolicyCode(),r.terminalLevelPolicyCode()));
        }
        if(PublishConfigurationRequest.from((Map)demo.get("publish")).expectedRowVersion()!=16 || revision!=16)throw new AssertionError("Publish revision");
        if(ActivationRequest.from((Map)demo.get("firstActivation")).expectedPointerRowVersion()!=null)throw new AssertionError("First activation");
        var readiness=new PublicationReadinessValidator().validate(parent,rules,bands,thresholds,slas);
        if(!readiness.ready())throw new AssertionError(JSON.writeValueAsString(readiness));
        var empty=new ConfigurationVersionDetails(parent.version(),parent.description(),parent.scorePolicyCode(),parent.maxFinalScore(),0);
        write("examples/errors/configuration-not-ready.json",sorted("status",409,"code","CONFIGURATION_NOT_READY","message","Configuration is not ready for publication","timestamp","2026-10-16T12:00:00Z","path","/api/v1/configuration/versions/1/publish","readiness",new PublicationReadinessValidator().validate(empty,List.of(),List.of(),List.of(),List.of())));
        var hash=new ConfigurationContentHash(JSON).computePublished(parent,11,rules,bands,thresholds,slas);
        var content=JSON.readValue(hash.json(),Map.class);
        var pinned=sorted("versionId","1","versionRowVersion","17","publishedAt","2026-10-16T00:01:00Z","publishedByUserId","11", "contentHash",hash.hash(),"hashFormat","configuration-content-v2","configuration",content);
        write("examples/configuration/pinned-snapshot.json",pinned);
        var active=new TreeMap<String,Object>(pinned);active.putAll(Map.of("pointerRowVersion","0","activatedAt","2026-10-16T00:02:00Z","activatedByUserId","11"));
        write("examples/configuration/active-snapshot.json",active);
        write("examples/configuration/published.json",sorted("versionId","1","rowVersion","17","publicationState","PUBLISHED","contentHash",hash.hash(),"hashFormat","configuration-content-v2","publishedAt","2026-10-16T00:01:00Z","publishedByUserId","11"));
        write("examples/configuration/rules.json",rules.stream().map(RiskRule::response).toList());
        write("examples/configuration/bands.json",bands.stream().map(RiskBand::response).toList());
        write("examples/configuration/thresholds.json",thresholds.stream().map(ApprovalThreshold::response).toList());
        write("examples/configuration/slas.json",slas.stream().map(ApprovalSla::response).toList());
        write("examples/configuration/saved-rule.json",new DraftRiskRuleService.SavedRule(rules.getFirst().response(),"1"));
        write("examples/configuration/saved-band.json",new DraftRiskBandService.SavedBand(bands.getFirst().response(),"8"));
        write("examples/configuration/saved-threshold.json",new DraftApprovalThresholdService.SavedThreshold(thresholds.getFirst().response(),"11"));
        write("examples/configuration/saved-sla.json",new DraftApprovalSlaService.SavedSla(slas.getFirst().response(),"14"));
        var config=new PublishedConfigurationMapping().map(new SubmissionAdapters.PublishedSnapshot("1","17","PUBLISHED","configuration-content-v2",hash.hash(),hash.json()));
        var canonical=new CanonicalAcceptance().configuration(config);
        var engineRoot=JSON.readValue(canonical.json(),Map.class);
        write("examples/risk/configuration-snapshot.json",sorted("assessmentId","701","rowVersion","4","status","COMPLETED","snapshotId","801","configurationHash",canonical.hash(),"evaluatorVersion","risk-scoring-v1","configuration",engineRoot.get("configuration")));
        var command=SubmissionCommand.decode(Files.readString(root.resolve("examples/risk/submission.json")));
        var cutoff=Instant.parse("2026-10-16T12:00:00.123456789Z");
        var facts=new FactsProviderProtocol().decode(Files.readAllBytes(root.resolve("examples/providers/facts-response.json")),command,cutoff,"payment-facts-provider");
        var accepted=facts.acceptance(cutoff,config);
        var protocol=new EvidenceProviderProtocol(accepted,"supplier-evidence-provider",100);
        var request=JSON.readValue(new String(protocol.request(0,null),java.nio.charset.StandardCharsets.UTF_8),Map.class);
        write("examples/providers/evidence-request.json",request);
        String replay=(String)request.get("replayReference");
        var scope=(Map<String,Object>)request.get("request");var coverage=(Map<String,Object>)scope.get("requiredCoverage");
        var population=sorted("paymentHistory",sorted("availability","COMPLETE","sourceReference",replay+"-paymentHistory","sourceRevision","1","coveredFrom",coverage.get("paymentFrom"),"records",List.of()),
            "supplier",sorted("availability","COMPLETE","sourceReference",replay+"-supplier","sourceRevision","1","coveredFrom",cutoff.toString(),"records",List.of(sorted("recordReference","supplier-301","supplierId","301","createdAt","2025-01-01T00:00:00Z","statusAsOfCutoff","ACTIVE"))),
            "supplierTransitions",sorted("availability","COMPLETE","sourceReference",replay+"-supplierTransitions","sourceRevision","1","coveredFrom",coverage.get("transitionFrom"),"records",List.of()));
        var response=new TreeMap<String,Object>(population);response.putAll(sorted("contractVersion","risk-upstream-evidence-v1","request",scope,"providerServiceId","supplier-evidence-provider","replayReference",replay,"snapshotRevision","1","populationHash",digest(population),
            "certification",Map.of("immutable",true,"asOfCutoff",true,"supplierWideAcrossRequesters",true,"exclusionsApplied",true,"factsReceiptBindingVerified",true),
            "pagination",sorted("pageIndex",0,"pageSize",100,"totalPages",1,"totalPayments",0,"totalTransitions",0,"nextCursor",null)));
        var collector=protocol.collector();collector.accept(JSON.writeValueAsBytes(response),10,1000);var upstream=collector.finish(false);
        write("examples/providers/evidence-response.json",response);
        var prior=new RiskInput.Evidence<RiskInput.PriorAssessment>(RiskInput.Availability.COMPLETE,"local-prior-demo","301","INR",cutoff,cutoff.minusSeconds(7776000),List.of());
        var input=new RiskInput(accepted.current(),cutoff,accepted.currentAndRetrySubmissionIds(),upstream.payments(),upstream.supplier(),upstream.transitions(),prior);
        var result=new RiskScoringEngine().evaluate(config,input);
        if(result.status()!=RiskResult.Status.COMPLETED || result.rawTotal().signum()!=0 || result.finalScore()!=0 || !"LOW".equals(result.riskBand()))throw new AssertionError("Demo result");
        write("examples/risk/factors.json",Map.of("assessmentId","701","rowVersion","4","status","COMPLETED","factors",result.factors().stream().map(f->Map.of("ruleCode",f.ruleCode().name(),"evaluationOrder",config.rules().stream().filter(r->r.code()==f.ruleCode()).findFirst().orElseThrow().evaluationOrder(),"outcome",f.outcome().name(),"configuredWeight",f.configuredWeight(),"contribution",f.contribution(),"explanation",f.explanation())).toList()));
        write("validation/java-validation-result.json",sorted("attestationFormat","sha256-lf-normalized-text-v1","runtimeSourceSha256",runtimeFingerprint(),"verifiedFiles",new TreeMap<>(VERIFIED_FILES),"recordFieldSetsVerified",checkedFields,"ready",readiness.ready(),"ruleCount",rules.size(),"draftPutRequests",revision,"contentHash",hash.hash(),"configurationHash",canonical.hash(),"productionMappingVerified",true,"factsDecoderVerified",true,"evidenceCollectorVerified",true,"engineResultVerified",true,"oracleAccess",false,"networkAccess",false));
        System.out.println("PASS: real request parsers, readiness, V2 hashing, production mapping, Facts decoder, evidence collector, seven-rule engine. No network or database.");
    }
}
