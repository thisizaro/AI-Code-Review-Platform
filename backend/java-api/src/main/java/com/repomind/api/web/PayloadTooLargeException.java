package com.repomind.api.web;

import java.io.IOException;

/** Thrown while reading a request body that grows past the allowed size. */
public class PayloadTooLargeException extends IOException {
	public PayloadTooLargeException() {
		super("Request body is too large.");
	}
}
