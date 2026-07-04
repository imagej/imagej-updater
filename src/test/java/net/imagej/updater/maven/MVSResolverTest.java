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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import net.imagej.updater.maven.MVSResolver.MediationWarning;
import net.imagej.updater.maven.MVSResolver.Resolution;
import net.imagej.updater.maven.MVSResolver.Root;
import net.imagej.updater.maven.MVSResolver.WarningType;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Runs every golden mediation vector (shared with the db-xml-maven
 * reference implementation) through {@link MVSResolver}, in declared root
 * order and reversed — results must be enable-order independent.
 */
public class MVSResolverTest {

	@Test
	public void testAllVectors() throws Exception {
		final List<String> names = vectorNames();
		assertTrue("no vectors found", names.size() >= 12);
		for (final String name : names) {
			runVector(name, false);
			runVector(name, true);
		}
	}

	private void runVector(final String name, final boolean reverseRoots)
		throws Exception
	{
		final InputStream in =
			getClass().getResourceAsStream("vectors/" + name);
		assertTrue("missing vector resource: " + name, in != null);
		final Document document = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder().parse(in);
		final Element vectorEl = document.getDocumentElement();
		assertEquals("mediation-vector", vectorEl.getNodeName());

		final Element catalogEl =
			IndexXmlReader.children(vectorEl, "catalog").get(0);
		final ComponentCatalog catalog = IndexXmlReader.parseCatalog(catalogEl);

		final Element rootsEl = IndexXmlReader.children(vectorEl, "roots").get(0);
		final List<Root> roots = new ArrayList<>();
		for (final Element rootEl : IndexXmlReader.children(rootsEl, "root")) {
			final Coordinate coordinate =
				Coordinate.parse(rootEl.getAttribute("coordinate"));
			roots.add(new Root(coordinate.ga(), coordinate.version(),
				"true".equals(rootEl.getAttribute("pinned"))));
		}
		final List<Exclusion> rootExclusions = new ArrayList<>();
		for (final Element exclEl : IndexXmlReader.children(rootsEl, "exclude")) {
			rootExclusions.add(Exclusion.parse(exclEl.getTextContent().trim()));
		}
		if (reverseRoots) Collections.reverse(roots);

		final Element expectedEl =
			IndexXmlReader.children(vectorEl, "expected").get(0);
		final Map<GA, String> expectedSelected = new HashMap<>();
		for (final Element selEl : IndexXmlReader.children(expectedEl, "select")) {
			final Coordinate coordinate =
				Coordinate.parse(selEl.getAttribute("coordinate"));
			expectedSelected.put(coordinate.ga(), coordinate.version());
		}
		final Set<MediationWarning> expectedWarnings = new HashSet<>();
		for (final Element warnEl : IndexXmlReader.children(expectedEl, "warning")) {
			expectedWarnings.add(new MediationWarning(
				WarningType.fromLabel(warnEl.getAttribute("type")),
				GA.parse(warnEl.getAttribute("coordinate"))));
		}

		final Resolution resolution =
			new MVSResolver().resolve(catalog, roots, rootExclusions);

		final String label = name + (reverseRoots ? " (reversed roots)" : "");
		assertEquals(label + ": selection mismatch", //
			readable(expectedSelected), readable(resolution.selected()));
		assertEquals(label + ": warnings mismatch", //
			readable(expectedWarnings),
			readable(new HashSet<>(resolution.warnings())));
	}

	private Map<String, String> readable(final Map<GA, String> selected) {
		final Map<String, String> result = new TreeMap<>();
		for (final Map.Entry<GA, String> entry : selected.entrySet()) {
			result.put(entry.getKey().toString(), entry.getValue());
		}
		return result;
	}

	private Set<String> readable(final Set<MediationWarning> warnings) {
		final Set<String> result = new TreeSet<>();
		for (final MediationWarning warning : warnings) {
			result.add(warning.toString());
		}
		return result;
	}

	private List<String> vectorNames() throws IOException {
		final InputStream in =
			getClass().getResourceAsStream("vectors/index.txt");
		assertTrue("vectors/index.txt resource missing", in != null);
		final List<String> names = new ArrayList<>();
		try (final BufferedReader reader =
			new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (!line.isEmpty()) names.add(line);
			}
		}
		return names;
	}
}
