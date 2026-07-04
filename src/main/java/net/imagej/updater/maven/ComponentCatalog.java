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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The union of component facts visible to mediation: all
 * {@code <component>} blocks (across all enabled sites) plus the
 * {@code <managed>} BOM entries.
 */
public final class ComponentCatalog {

	private final Map<GA, MavenComponent> components = new LinkedHashMap<>();
	private final Map<GA, ManagedEntry> managed = new LinkedHashMap<>();
	private String bom;

	public Map<GA, MavenComponent> components() { return components; }
	public Map<GA, ManagedEntry> managed() { return managed; }

	public String bom() { return bom; }
	public void setBom(final String bom) { this.bom = bom; }

	public MavenComponent component(final GA ga) {
		MavenComponent c = components.get(ga);
		if (c == null) {
			c = new MavenComponent(ga);
			components.put(ga, c);
		}
		return c;
	}

	public Release release(final GA ga, final String version) {
		final MavenComponent c = components.get(ga);
		return c == null ? null : c.release(version);
	}

	/** Merges another site's catalog into this one (union of facts). */
	public void merge(final ComponentCatalog other) {
		for (final MavenComponent theirs : other.components.values()) {
			final MavenComponent mine = component(theirs.ga());
			for (final Release release : theirs.releases().values()) {
				// Facts derive from immutable POMs; a full entry wins over a
				// slim entry, otherwise first writer wins.
				final Release existing = mine.release(release.version());
				if (existing == null || (existing.slim() && !release.slim())) {
					mine.add(release);
				}
			}
			if (theirs.offered()) mine.setOffered(true);
			if (theirs.current() != null) mine.setCurrent(theirs.current());
		}
		for (final ManagedEntry entry : other.managed.values()) {
			if (!managed.containsKey(entry.ga())) managed.put(entry.ga(), entry);
		}
		if (bom == null) bom = other.bom;
	}
}
