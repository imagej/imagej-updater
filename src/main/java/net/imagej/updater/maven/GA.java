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

/**
 * Component identity: groupId:artifactId, plus classifier and packaging.
 * <p>
 * Version is deliberately <em>not</em> part of identity — it is the variable
 * that mediation selects. Default classifier is {@code ""} and default
 * packaging {@code "jar"}; the string form omits them when default. Mirrors
 * {@code dbxm.model.GA} in the db-xml-maven reference implementation.
 * </p>
 */
public final class GA implements Comparable<GA> {

	private final String group;
	private final String artifact;
	private final String classifier;
	private final String packaging;

	public GA(final String group, final String artifact) {
		this(group, artifact, "", "jar");
	}

	public GA(final String group, final String artifact,
		final String classifier, final String packaging)
	{
		this.group = group;
		this.artifact = artifact;
		this.classifier = classifier == null ? "" : classifier;
		this.packaging = packaging == null || packaging.isEmpty() ? "jar" : packaging;
	}

	public String group() { return group; }
	public String artifact() { return artifact; }
	public String classifier() { return classifier; }
	public String packaging() { return packaging; }

	/** Parses {@code "g:a"} or {@code "g:a:c:p"}. */
	public static GA parse(final String s) {
		final String[] tokens = s.split(":", -1);
		if (tokens.length == 2) return new GA(tokens[0], tokens[1]);
		if (tokens.length == 4) {
			return new GA(tokens[0], tokens[1], tokens[2], tokens[3]);
		}
		throw new IllegalArgumentException(
			"Invalid GA (expected g:a or g:a:c:p): " + s);
	}

	@Override
	public boolean equals(final Object o) {
		if (!(o instanceof GA)) return false;
		final GA other = (GA) o;
		return group.equals(other.group) && artifact.equals(other.artifact) &&
			classifier.equals(other.classifier) && packaging.equals(other.packaging);
	}

	@Override
	public int hashCode() {
		return ((group.hashCode() * 31 + artifact.hashCode()) * 31 +
			classifier.hashCode()) * 31 + packaging.hashCode();
	}

	@Override
	public String toString() {
		if (classifier.isEmpty() && "jar".equals(packaging)) {
			return group + ":" + artifact;
		}
		return group + ":" + artifact + ":" + classifier + ":" + packaging;
	}

	@Override
	public int compareTo(final GA other) {
		return toString().compareTo(other.toString());
	}
}
