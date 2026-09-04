package dev.booking.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Handles the two outcomes that are not booking outcomes: a malformed request,
 * and a defect.
 *
 * Business rejections do not come through here — they are values returned by the
 * core and mapped by [BookingOutcomeResponder].  This advice exists so that the
 * remaining branches are logged too, since an unlogged branch is invisible in
 * production.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Shape validation — a caller error, not a rule violation. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onInvalidRequest(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.joinToString(", ") { "${it.field} ${it.defaultMessage}" }
        log.info("request rejected reason=malformed fields=[{}]", fields)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "BAD_REQUEST", rule = "shape", message = fields))
    }

    /**
     * Anything reaching here is a defect.  It is logged once, with context, and
     * reported as a 500 — never mapped onto a business outcome, because pretending
     * a bug is a business rejection is how bugs stay hidden.
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled defect while serving request", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = "INTERNAL", rule = "none", message = "internal error"))
    }
}
