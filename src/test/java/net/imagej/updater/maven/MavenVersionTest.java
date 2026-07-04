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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Validates {@link MavenVersion} ordering against the shared corpus
 * ({@code vectors/version-order.txt}), which the Python reference
 * implementation (via jgo's comparator) must order identically.
 */
public class MavenVersionTest {

	@Test
	public void testCorpusOrdering() throws IOException {
		final List<List<String>> groups = loadCorpus();
		assertTrue("corpus suspiciously small", groups.size() > 10);
		for (final List<String> group : groups) {
			final String first = group.get(0);
			for (final String other : group.subList(1, group.size())) {
				assertEquals(first + " != " + other, 0,
					MavenVersion.compare(first, other));
			}
		}
		for (int i = 0; i < groups.size() - 1; i++) {
			for (final String a : groups.get(i)) {
				for (final String b : groups.get(i + 1)) {
					assertTrue("expected " + a + " < " + b,
						MavenVersion.compare(a, b) < 0);
					assertTrue("expected " + b + " > " + a,
						MavenVersion.compare(b, a) > 0);
				}
			}
		}
	}

	private List<List<String>> loadCorpus() throws IOException {
		final InputStream in =
			getClass().getResourceAsStream("vectors/version-order.txt");
		assertTrue("version-order.txt resource missing", in != null);
		final List<List<String>> groups = new ArrayList<>();
		try (final BufferedReader reader =
			new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				if (line.startsWith("=")) {
					groups.get(groups.size() - 1).add(line.substring(1));
				}
				else {
					final List<String> group = new ArrayList<>();
					group.add(line);
					groups.add(group);
				}
			}
		}
		return groups;
	}
}
