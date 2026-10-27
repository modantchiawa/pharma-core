package com.pharmacore.shared.web;

import com.pharmacore.shared.context.MissingTenantContextException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem details for domain errors. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        var problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage());
        problem.setProperty("code", e.code());
        e.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification",
                "The resource was modified concurrently, reload and retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "integrity-violation", "The request conflicts with existing data");
    }

    @ExceptionHandler(MissingTenantContextException.class)
    ProblemDetail missingTenant(MissingTenantContextException e) {
        return problem(HttpStatus.FORBIDDEN, "tenant-required", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://pharmacore.io/problems/" + type));
        return problem;
    }
}
