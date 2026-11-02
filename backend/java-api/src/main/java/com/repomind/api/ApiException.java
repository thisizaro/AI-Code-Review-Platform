package com.repomind.api;

/** An error that maps directly to an HTTP status and a {"message": ...} body. */
public class ApiException extends RuntimeException {

	private final int status;

	public ApiException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
