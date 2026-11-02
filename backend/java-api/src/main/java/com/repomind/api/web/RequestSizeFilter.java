package com.repomind.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies. Every real request is a few hundred bytes of JSON plus a job description of at
 * most 10,000 characters. A declared Content-Length is checked up front; chunked bodies are counted as they are read.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {

	private final long maxBytes;

	public RequestSizeFilter(@Value("${repomind.max-body-bytes:65536}") long maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			response.setStatus(413);
			response.setContentType("application/json");
			response.getWriter().write("{\"message\":\"Request body is too large.\"}");
			return;
		}
		chain.doFilter(request.getContentLengthLong() < 0 ? new Limited(request, maxBytes) : request, response);
	}

	private static final class Limited extends HttpServletRequestWrapper {
		private final long max;
		private ServletInputStream stream;

		Limited(HttpServletRequest request, long max) {
			super(request);
			this.max = max;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new Counting(super.getInputStream(), max);
			}
			return stream;
		}
	}

	private static final class Counting extends ServletInputStream {
		private final ServletInputStream in;
		private final long max;
		private long read;

		Counting(ServletInputStream in, long max) {
			this.in = in;
			this.max = max;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0 && ++read > max) {
				throw new PayloadTooLargeException();
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int n = in.read(buf, off, len);
			if (n > 0 && (read += n) > max) {
				throw new PayloadTooLargeException();
			}
			return n;
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}
	}
}
