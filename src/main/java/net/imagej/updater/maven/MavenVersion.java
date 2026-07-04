/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2025 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.updater.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maven version ordering, per the version-order specification:
 * https://maven.apache.org/pom.html#version-order-specification
 * <p>
 * This is a port of jgo's implementation ({@code jgo.maven._version}),
 * which is the normative reference for this project; both must order the
 * shared corpus in {@code vectors/version-order.txt} identically.
 * </p>
 */
public final class MavenVersion implements Comparable<MavenVersion> {

	// Qualifier ordering per Maven spec:
	// alpha < beta < milestone < rc = cr < snapshot < "" = final = ga = release < sp
	private static final Map<String, Integer> QUALIFIER_ORDER = new HashMap<>();
	static {
		QUALIFIER_ORDER.put("alpha", -5);
		QUALIFIER_ORDER.put("a", -5);
		QUALIFIER_ORDER.put("beta", -4);
		QUALIFIER_ORDER.put("b", -4);
		QUALIFIER_ORDER.put("milestone", -3);
		QUALIFIER_ORDER.put("m", -3);
		QUALIFIER_ORDER.put("rc", -2);
		QUALIFIER_ORDER.put("cr", -2);
		QUALIFIER_ORDER.put("snapshot", -1);
		QUALIFIER_ORDER.put("", 0);
		QUALIFIER_ORDER.put("final", 0);
		QUALIFIER_ORDER.put("ga", 0);
		QUALIFIER_ORDER.put("release", 0);
		QUALIFIER_ORDER.put("sp", 1);
	}

	private static final class Token {
		final boolean numeric;
		final long num;
		final String str; // lowercased qualifier; only when !numeric
		final char sep; // '.', '-', '_', or 0 for the first token

		Token(final long num, final char sep) {
			this.numeric = true;
			this.num = num;
			this.str = null;
			this.sep = sep;
		}

		Token(final String str, final char sep) {
			this.numeric = false;
			this.num = 0;
			this.str = str;
			this.sep = sep;
		}

		boolean isNull() {
			return numeric ? num == 0 : //
				str.isEmpty() || "final".equals(str) || "ga".equals(str) ||
				"release".equals(str);
		}
	}

	private final String original;
	private final List<Token> tokens;

	public MavenVersion(final String version) {
		original = version;
		tokens = trimNulls(tokenize(version.trim()));
	}

	@Override
	public String toString() {
		return original;
	}

	@Override
	public boolean equals(final Object o) {
		return o instanceof MavenVersion && compareTo((MavenVersion) o) == 0;
	}

	@Override
	public int hashCode() {
		// Coarse but consistent with equals: equal versions trim to
		// token lists of equal length.
		return tokens.size();
	}

	public static int compare(final String v1, final String v2) {
		return new MavenVersion(v1).compareTo(new MavenVersion(v2));
	}

	@Override
	public int compareTo(final MavenVersion other) {
		final List<Token> a = tokens, b = other.tokens;
		final int maxLen = Math.max(a.size(), b.size());
		for (int i = 0; i < maxLen; i++) {
			final Token tokA = i < a.size() ? a.get(i) : null;
			final Token tokB = i < b.size() ? b.get(i) : null;
			// Padding separator comes from the corresponding position in
			// the longer version (per spec: pad with the shorter's "null"
			// of the other's separator kind).
			final char padSepA = i < b.size() ? b.get(i).sep : '.';
			final char padSepB = i < a.size() ? a.get(i).sep : '.';
			final int result = compareTokens(tokA, tokB, padSepA, padSepB);
			if (result != 0) return result;
		}
		return 0;
	}

	// -- Tokenization --

	private static List<Token> tokenize(final String version) {
		final List<Token> tokens = new ArrayList<>();
		if (version.isEmpty()) return tokens;

		final StringBuilder current = new StringBuilder();
		char currentSep = 0;
		int prevIsDigit = -1; // -1 unknown, 0 letter, 1 digit

		for (int i = 0; i < version.length(); i++) {
			final char c = version.charAt(i);
			if (c == '.' || c == '-' || c == '_') {
				if (current.length() > 0 || !tokens.isEmpty()) {
					tokens.add(makeToken(current.toString(), currentSep));
				}
				current.setLength(0);
				currentSep = c;
				prevIsDigit = -1;
			}
			else if (Character.isDigit(c)) {
				if (prevIsDigit == 0 && current.length() > 0) {
					tokens.add(makeToken(current.toString(), currentSep));
					current.setLength(0);
					currentSep = '-'; // digit/letter transitions act as hyphens
				}
				current.append(c);
				prevIsDigit = 1;
			}
			else {
				if (prevIsDigit == 1 && current.length() > 0) {
					tokens.add(makeToken(current.toString(), currentSep));
					current.setLength(0);
					currentSep = '-';
				}
				current.append(c);
				prevIsDigit = 0;
			}
		}
		if (current.length() > 0 || !tokens.isEmpty()) {
			tokens.add(makeToken(current.toString(), currentSep));
		}
		return tokens;
	}

	private static Token makeToken(final String s, final char sep) {
		if (s.isEmpty()) return new Token(0L, sep);
		boolean numeric = true;
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				numeric = false;
				break;
			}
		}
		if (numeric) return new Token(Long.parseLong(s), sep);
		return new Token(s.toLowerCase(), sep);
	}

	private static List<Token> trimNulls(final List<Token> tokens) {
		final List<Token> result = new ArrayList<>(tokens);
		while (!result.isEmpty() && result.get(result.size() - 1).isNull()) {
			result.remove(result.size() - 1);
		}
		return result;
	}

	// -- Token comparison --

	private static int compareTokens(final Token a, final Token b,
		final char padSepA, final char padSepB)
	{
		if (a == null && b == null) return 0;

		// Effective values and separators; padding value is numeric 0 for
		// '.' separators and the empty qualifier otherwise.
		final boolean aNumeric = a != null ? a.numeric : padSepA == '.';
		final boolean bNumeric = b != null ? b.numeric : padSepB == '.';
		final long aNum = a != null ? a.num : 0;
		final long bNum = b != null ? b.num : 0;
		final String aStr = a != null ? a.str : "";
		final String bStr = b != null ? b.str : "";
		final char sepA = a != null ? a.sep : padSepA;
		final char sepB = b != null ? b.sep : padSepB;

		if (aNumeric && bNumeric) {
			// Separator dominates for numbers: -number < .number.
			if (sepA != sepB && isDelimiter(sepA) && isDelimiter(sepB)) {
				if (sepA == '.' && (sepB == '-' || sepB == '_')) return 1;
				if ((sepA == '-' || sepA == '_') && sepB == '.') return -1;
			}
			return Long.compare(aNum, bNum);
		}
		if (!aNumeric && !bNumeric) {
			final Integer knownA = QUALIFIER_ORDER.get(aStr);
			final Integer knownB = QUALIFIER_ORDER.get(bStr);
			final int prioA = knownA != null ? knownA : 0;
			final int prioB = knownB != null ? knownB : 0;
			if (prioA != prioB) return Integer.compare(prioA, prioB);
			// Same priority: known qualifiers compare as empty strings,
			// unknown ones alphabetically.
			final String cmpA = knownA != null ? "" : aStr;
			final String cmpB = knownB != null ? "" : bStr;
			return cmpA.compareTo(cmpB);
		}
		// Mixed: number > qualifier.
		return aNumeric ? 1 : -1;
	}

	private static boolean isDelimiter(final char c) {
		return c == '.' || c == '-' || c == '_';
	}
}
