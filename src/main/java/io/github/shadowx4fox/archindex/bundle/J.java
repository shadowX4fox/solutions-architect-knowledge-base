package io.github.shadowx4fox.archindex.bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * JSON read as plain maps and lists — the bundle is data written by the plugin, and reading it untyped keeps the
 * server tolerant of fields a newer plugin adds.
 */
public final class J {

	public static final JsonMapper MAPPER = JsonMapper.builder().build();

	private J() {
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(String json) {
		return MAPPER.readValue(json, LinkedHashMap.class);
	}

	public static Map<String, Object> obj(byte[] json) {
		return obj(new String(json, StandardCharsets.UTF_8));
	}

	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	public static String pretty(Object value) {
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object o, String key) {
		Object v = o instanceof Map<?, ?> m ? m.get(key) : null;
		return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Object o, String key) {
		Object v = o instanceof Map<?, ?> m ? m.get(key) : null;
		return v instanceof List<?> ? (List<Object>) v : List.of();
	}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> maps(Object o, String key) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object x : list(o, key)) {
			if (x instanceof Map<?, ?> m) {
				out.add((Map<String, Object>) m);
			}
		}
		return out;
	}

	public static List<String> strings(Object o, String key) {
		List<String> out = new ArrayList<>();
		for (Object x : list(o, key)) {
			if (x != null) {
				out.add(String.valueOf(x));
			}
		}
		return out;
	}

	public static String str(Object o, String key) {
		Object v = o instanceof Map<?, ?> m ? m.get(key) : null;
		return v == null ? null : String.valueOf(v);
	}

	/** Equality as the plugin's {@code JSON.stringify(a ?? null) === JSON.stringify(b ?? null)}. */
	public static boolean same(Object a, Object b) {
		return Objects.equals(write(a), write(b));
	}

	/**
	 * The paths {@code b} adds to {@code a} when it only extends it — every value of {@code a} unchanged in {@code b},
	 * the difference being keys an object of {@code a} does not have, at any depth — or empty when it does not. An
	 * array element added or removed, or a changed value, is not an extension. Paths read {@code .tdrs[0].components}.
	 */
	public static Optional<List<String>> extensions(Object a, Object b) {
		List<String> added = new ArrayList<>();
		return extends_(a, b, "", added) ? Optional.of(added) : Optional.empty();
	}

	private static boolean extends_(Object a, Object b, String at, List<String> added) {
		if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
			for (Map.Entry<?, ?> e : ma.entrySet()) {
				if (!mb.containsKey(e.getKey()) || !extends_(e.getValue(), mb.get(e.getKey()), at + "." + e.getKey(), added)) {
					return false;
				}
			}
			for (Object k : mb.keySet()) {
				if (!ma.containsKey(k)) {
					added.add(at + "." + k);
				}
			}
			return true;
		}
		if (a instanceof List<?> la && b instanceof List<?> lb) {
			if (la.size() != lb.size()) {
				return false;
			}
			for (int i = 0; i < la.size(); i++) {
				if (!extends_(la.get(i), lb.get(i), at + "[" + i + "]", added)) {
					return false;
				}
			}
			return true;
		}
		return same(a, b);
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sha256(String text) {
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	/** A name-based UUID the way the plugin cuts one: version 5 layout over a SHA-256. */
	public static String uuidOf(String s) {
		String h = sha256(s);
		int variant = (Integer.parseInt(h.substring(16, 17), 16) & 3) | 8;
		return h.substring(0, 8) + "-" + h.substring(8, 12) + "-5" + h.substring(13, 16) + "-"
				+ Integer.toHexString(variant) + h.substring(17, 20) + "-" + h.substring(20, 32);
	}

}
