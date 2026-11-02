package com.repomind.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipTestUtil {
	private ZipTestUtil() {}

	/** Builds a zip like GitHub's zipball: every path sits under one root folder. */
	public static byte[] zip(Map<String, ?> entries) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, ?> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				Object v = e.getValue();
				if (v != null) {
					zip.write(v instanceof byte[] b ? b : v.toString().getBytes(StandardCharsets.UTF_8));
				}
				zip.closeEntry();
			}
			zip.finish();
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Map<String, Object> files(Object... pathContentPairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pathContentPairs.length; i += 2) {
			map.put((String) pathContentPairs[i], pathContentPairs[i + 1]);
		}
		return map;
	}
}
