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

import java.util.Collections;
import java.util.List;

/**
 * A direct dependency edge: target identity at a concrete version, with
 * per-edge exclusions. Edges are publish-time facts (post model-building);
 * version ranges are forbidden by the index format.
 */
public final class DependencyEdge {

	private final GA ga;
	private final String version;
	private final List<Exclusion> exclusions;
	private final String platform;
	private final boolean optional;

	public DependencyEdge(final GA ga, final String version,
		final List<Exclusion> exclusions, final String platform,
		final boolean optional)
	{
		this.ga = ga;
		this.version = version;
		this.exclusions = exclusions == null ? //
			Collections.<Exclusion> emptyList() : //
			Collections.unmodifiableList(exclusions);
		this.platform = platform;
		this.optional = optional;
	}

	public GA ga() { return ga; }
	public String version() { return version; }
	public List<Exclusion> exclusions() { return exclusions; }
	public String platform() { return platform; }
	public boolean optional() { return optional; }

	@Override
	public String toString() {
		return ga + ":" + version;
	}
}
