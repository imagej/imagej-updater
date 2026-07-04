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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads {@code <component>} and {@code <managed>} blocks of the
 * Maven-native index format (doc/maven-native-update-sites.md §3) into a
 * {@link ComponentCatalog}.
 * <p>
 * These elements coexist with legacy {@code <plugin>} entries in
 * {@code db.xml.gz}; legacy elements are ignored here and continue to be
 * handled by {@link net.imagej.updater.XMLFileReader}.
 * </p>
 */
public final class IndexXmlReader {

	private IndexXmlReader() {}

	/** Parses a whole index document from a stream. */
	public static ComponentCatalog parse(final InputStream in)
		throws IOException
	{
		try {
			final DocumentBuilderFactory factory =
				DocumentBuilderFactory.newInstance();
			final Document document = factory.newDocumentBuilder().parse(in);
			return parseCatalog(document.getDocumentElement());
		}
		catch (final ParserConfigurationException | SAXException exc) {
			throw new IOException(exc);
		}
	}

	/** Parses {@code <component>} children of the given element. */
	public static ComponentCatalog parseCatalog(final Element parent) {
		final ComponentCatalog catalog = new ComponentCatalog();
		for (final Element el : children(parent, "component")) {
			final MavenComponent component = parseComponent(el);
			catalog.components().put(component.ga(), component);
		}
		return catalog;
	}

	public static MavenComponent parseComponent(final Element el) {
		final MavenComponent component =
			new MavenComponent(GA.parse(el.getAttribute("coordinate")));
		component.setOffered("true".equals(el.getAttribute("offered")));
		if (!el.getAttribute("current").isEmpty()) {
			component.setCurrent(el.getAttribute("current"));
		}
		for (final Element relEl : children(el, "release")) {
			final Release release = new Release(relEl.getAttribute("version"));
			if (!relEl.getAttribute("sha1").isEmpty()) {
				release.setSha1(relEl.getAttribute("sha1"));
			}
			if (!relEl.getAttribute("timestamp").isEmpty()) {
				release.setTimestamp(relEl.getAttribute("timestamp"));
			}
			if (!relEl.getAttribute("filesize").isEmpty()) {
				release.setFilesize(Long.parseLong(relEl.getAttribute("filesize")));
			}
			if (!relEl.getAttribute("min-java").isEmpty()) {
				release.setMinJava(Integer.parseInt(relEl.getAttribute("min-java")));
			}
			release.setSlim("true".equals(relEl.getAttribute("slim")));
			for (final Element descEl : children(relEl, "description")) {
				release.setDescription(descEl.getTextContent());
			}
			for (final Element selEl : children(relEl, "selection")) {
				for (final Element sEl : children(selEl, "select")) {
					final Coordinate coordinate =
						Coordinate.parse(sEl.getAttribute("coordinate"));
					release.selection().put(coordinate.ga(), coordinate.version());
				}
			}
			for (final Element depEl : children(relEl, "dependency")) {
				release.edges().add(parseDependency(depEl));
			}
			component.add(release);
		}
		return component;
	}

	private static DependencyEdge parseDependency(final Element depEl) {
		final Coordinate coordinate =
			Coordinate.parse(depEl.getAttribute("coordinate"));
		GA ga = coordinate.ga();
		final String classifier = depEl.getAttribute("classifier");
		final String packaging = depEl.getAttribute("packaging");
		if (!classifier.isEmpty() || !packaging.isEmpty()) {
			ga = new GA(ga.group(), ga.artifact(), classifier, packaging);
		}
		final List<Exclusion> exclusions = new ArrayList<>();
		for (final Element exclEl : children(depEl, "exclude")) {
			exclusions.add(Exclusion.parse(exclEl.getTextContent().trim()));
		}
		final String platform = depEl.getAttribute("platform");
		return new DependencyEdge(ga, coordinate.version(), exclusions,
			platform.isEmpty() ? null : platform,
			"true".equals(depEl.getAttribute("optional")));
	}

	/** Direct child elements with the given tag name (non-recursive). */
	public static List<Element> children(final Element parent,
		final String tag)
	{
		final List<Element> result = new ArrayList<>();
		final NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			final Node node = nodes.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE &&
				tag.equals(node.getNodeName()))
			{
				result.add((Element) node);
			}
		}
		return result;
	}
}
