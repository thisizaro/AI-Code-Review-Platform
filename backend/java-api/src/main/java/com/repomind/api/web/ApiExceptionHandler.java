package com.repomind.api.web;

import com.repomind.api.ApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves the API as {"message": "..."}, which is what the frontend reads. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, String>> api(ApiException e) {
		return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof PayloadTooLargeException) {
				return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", t.getMessage()));
			}
		}
		return ResponseEntity.badRequest().body(Map.of("message", "Request body is missing or is not valid JSON."));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Map<String, String>> mediaType(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("message", "Content-Type must be application/json."));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Map<String, String>> method(HttpRequestMethodNotSupportedException e) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
		if (e.getSupportedHttpMethods() != null) {
			response.allow(e.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
		}
		return response.body(Map.of("message", "Method not allowed for this endpoint."));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Endpoint not found."));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, String>> unexpected(Exception e) {
		log.error("Unexpected error", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Unexpected server error."));
	}
}
