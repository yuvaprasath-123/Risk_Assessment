package com.ofss;
import com.ofss.dto.*;
import com.ofss.entity.*;
import com.ofss.service.PaymentProcessingService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:processing;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"})
class ProcessingIntegrationTest {
    @Autowired PaymentProcessingService service;
    @LocalServerPort int port;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    @Test void exposesEveryBlueprintProcessingPath() {
        var paths=mappings.getHandlerMethods().keySet().stream().flatMap(m->m.getPatternValues().stream()).collect(java.util.stream.Collectors.toSet());
        for(String path:java.util.List.of("/execute","/{paymentId}","/{paymentId}/retry","/{paymentId}/attempts","/{paymentId}/idempotency-status","/simulation-mode"))
            assertTrue(paths.contains("/api/v1/payment-processing"+path),path);
    }
    ProcessPaymentRequest request(String id,Boolean failure) { return new ProcessPaymentRequest(id,1L,new BigDecimal("100.00"),failure); }
    @Test void successIsProtectedAcrossRepeatedRequests() {
        assertEquals(ProcessingStatus.SUCCESS,service.process(request("success",false)).status());
        assertTrue(service.process(request("success",true)).idempotentReplay());
        assertTrue(service.retry("success",new RetryPaymentRequest(true)).idempotentReplay());
        assertEquals(1,service.attempts("success").size());
        assertTrue(service.idempotency("success").successfulExecutionProtected());
    }
    @Test void failedPaymentRetriesAndKeepsAllAttempts() {
        service.process(request("retry",true));
        assertFalse(service.idempotency("retry").successfulExecutionProtected());
        assertTrue(service.process(request("retry",false)).idempotentReplay());
        assertEquals(ProcessingStatus.SUCCESS,service.retry("retry",new RetryPaymentRequest(false)).status());
        assertEquals(2,service.attempts("retry").size());
        assertEquals(ProcessingStatus.FAILED,service.attempts("retry").getFirst().status);
        assertEquals(ProcessingStatus.SUCCESS,service.get("retry").status());
    }
    @Test void paymentIdCannotBeReusedWithAnotherAmount() {
        service.process(request("payload",false));
        assertThrows(ResponseStatusException.class,()->service.process(new ProcessPaymentRequest("payload",1L,new BigDecimal("200"),false)));
        assertEquals(1,service.attempts("payload").size());
    }
    @Test void concurrentRetriesExecuteOnlyOneSuccessfulAttempt() throws Exception {
        service.process(request("concurrent",true));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<ProcessPaymentResponse> retry=()->{start.await(); return service.retry("concurrent",new RetryPaymentRequest(false));};
            var a=pool.submit(retry); var b=pool.submit(retry); start.countDown();
            boolean aReplay=a.get(10,TimeUnit.SECONDS).idempotentReplay();
            boolean bReplay=b.get(10,TimeUnit.SECONDS).idempotentReplay();
            assertNotEquals(aReplay,bReplay); assertEquals(2,service.attempts("concurrent").size());
        }
    }
    @Test void savedSimulationModeCanBeOverriddenPerRequest() {
        service.simulation(new SimulationModeRequest(true));
        assertEquals(ProcessingStatus.FAILED,service.process(request("mode-default",null)).status());
        assertEquals(ProcessingStatus.SUCCESS,service.process(request("mode-override",false)).status());
        service.simulation(new SimulationModeRequest(false));
    }
    @Test void executeHttpEndpointValidatesAndReturnsStatus() throws Exception {
        var client=HttpClient.newHttpClient();
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payment-processing/execute"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"paymentId\":\"http\",\"supplierId\":1,\"amount\":100,\"simulateFailure\":false}")).build();
        assertEquals(201,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200,client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
