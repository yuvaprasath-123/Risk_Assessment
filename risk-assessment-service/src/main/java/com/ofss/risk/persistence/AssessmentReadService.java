package com.ofss.risk.persistence;

import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public class AssessmentReadService {
    private final AssessmentReadRepository repository; private final TransactionTemplate reads;
    public AssessmentReadService(AssessmentReadRepository repository,PlatformTransactionManager manager) {
        this.repository=repository; reads=new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public AssessmentReadView get(String assessmentId) {
        final long id;
        try { id=PersistenceValues.id(assessmentId); }
        catch(IllegalArgumentException e) { throw new AssessmentReadFailure(AssessmentReadFailure.Code.INVALID_ASSESSMENT_ID); }
        try { return reads.execute(status->{repository.beginSnapshot(); return repository.read(id);}); }
        catch(AssessmentReadFailure e) { throw e; }
        catch(RuntimeException e) { throw new AssessmentReadFailure(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE); }
    }
}
