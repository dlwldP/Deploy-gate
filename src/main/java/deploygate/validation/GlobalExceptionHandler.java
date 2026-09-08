package deploygate.validation;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "INVALID_REQUEST");
        body.put("errors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({
            ApprovalRequestNotFoundException.class,
            DeployerNotFoundException.class,
            StackNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleNotFoundException(RuntimeException exception) {
        return errorBody(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({
            DeployerNotAuthorizedException.class,
            ApproverNotAuthorizedException.class
    })
    public ResponseEntity<Map<String, Object>> handleForbiddenException(RuntimeException exception) {
        return errorBody(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage());
    }

    @ExceptionHandler({
            DuplicateVoteException.class,
            InvalidRequestStateException.class
    })
    public ResponseEntity<Map<String, Object>> handleConflictException(RuntimeException exception) {
        return errorBody(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    /**
     * Two approvers voted at the same instant and one lost the version race. Nothing is
     * wrong with the request itself, so the caller is told to simply vote again.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrentVote(OptimisticLockingFailureException exception) {
        return errorBody(HttpStatus.CONFLICT, "CONFLICT",
                "another approver voted on this request at the same time; retry the vote");
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String result, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
