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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimum Version Selection (MVS) mediation over a composed
 * {@link ComponentCatalog}.
 * <p>
 * This is the cross-site composition algorithm specified in
 * doc/maven-native-update-sites.md §4.1/§4.2, and must produce results
 * identical to the normative reference implementation ({@code dbxm.mvs}
 * in the db-xml-maven repository). Both are validated against the shared
 * golden vectors.
 * </p>
 * <ul>
 * <li>The installation is a synthetic root; user-selected components are
 * its direct dependencies ("roots").</li>
 * <li>Selection per {@link GA} is the highest version requested via any
 * surviving (non-excluded) path; because published edges carry concrete
 * versions only, MVS and highest-wins coincide.</li>
 * <li>Precedence: user pin &gt; BOM ({@code <managed>}) &gt; MVS.</li>
 * <li>Exclusions are per-path; root exclusions are global; BOM-entry
 * exclusions apply to the managed component's outgoing edges. Exclusions
 * never remove an explicitly selected root.</li>
 * <li>The walk traverses each component at its <em>selected</em> version,
 * so losing subtrees are pruned.</li>
 * </ul>
 */
public final class MVSResolver {

	/**
	 * Iteration cap for the fixpoint loop. Real dependency graphs converge
	 * in a handful of iterations; the cap guards against pathological
	 * oscillation.
	 */
	public static final int MAX_ITERATIONS = 1000;

	private static final Pattern MAJOR = Pattern.compile("^(\\d+)");

	/** A user-selected component: site current version, or a pinned one. */
	public static final class Root {

		private final GA ga;
		private final String version;
		private final boolean pinned;

		public Root(final GA ga, final String version, final boolean pinned) {
			this.ga = ga;
			this.version = version;
			this.pinned = pinned;
		}

		public GA ga() { return ga; }
		public String version() { return version; }
		public boolean pinned() { return pinned; }
	}

	public enum WarningType {
			HELD_BELOW("held-below"), //
			MAJOR_MISALIGNMENT("major-misalignment"), //
			MISSING_RELEASE("missing-release");

		private final String label;

		WarningType(final String label) {
			this.label = label;
		}

		public String label() { return label; }

		public static WarningType fromLabel(final String label) {
			for (final WarningType type : values()) {
				if (type.label.equals(label)) return type;
			}
			throw new IllegalArgumentException("Unknown warning type: " + label);
		}
	}

	/** A non-blocking mediation diagnostic. */
	public static final class MediationWarning {

		private final WarningType type;
		private final GA ga;

		public MediationWarning(final WarningType type, final GA ga) {
			this.type = type;
			this.ga = ga;
		}

		public WarningType type() { return type; }
		public GA ga() { return ga; }

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof MediationWarning)) return false;
			final MediationWarning other = (MediationWarning) o;
			return type == other.type && ga.equals(other.ga);
		}

		@Override
		public int hashCode() {
			return type.hashCode() * 31 + ga.hashCode();
		}

		@Override
		public String toString() {
			return type.label() + "(" + ga + ")";
		}
	}

	public static final class Resolution {

		private final Map<GA, String> selected;
		private final List<MediationWarning> warnings;
		private final Map<GA, Set<String>> requests;

		Resolution(final Map<GA, String> selected,
			final List<MediationWarning> warnings,
			final Map<GA, Set<String>> requests)
		{
			this.selected = Collections.unmodifiableMap(selected);
			this.warnings = Collections.unmodifiableList(warnings);
			this.requests = Collections.unmodifiableMap(requests);
		}

		/** Final selection: one version per reachable {@link GA}. */
		public Map<GA, String> selected() { return selected; }

		/** Deduplicated, deterministically ordered warnings. */
		public List<MediationWarning> warnings() { return warnings; }

		/** Surviving requests from the stable walk, for diagnostics. */
		public Map<GA, Set<String>> requests() { return requests; }
	}

	public Resolution resolve(final ComponentCatalog catalog,
		final List<Root> roots, final Collection<Exclusion> rootExclusions)
	{
		final Map<GA, String> pins = new HashMap<>();
		for (final Root root : roots) {
			if (root.pinned()) pins.put(root.ga(), root.version());
		}

		Map<GA, String> selected = new HashMap<>();
		Map<GA, Set<String>> requests = null;
		boolean converged = false;
		for (int i = 0; i < MAX_ITERATIONS; i++) {
			requests = walk(catalog, roots, rootExclusions, selected);
			final Map<GA, String> newSelected = new HashMap<>();
			for (final Map.Entry<GA, Set<String>> entry : requests.entrySet()) {
				final GA ga = entry.getKey();
				String version = highest(entry.getValue());
				if (pins.containsKey(ga)) version = pins.get(ga);
				else {
					final ManagedEntry managed = catalog.managed().get(ga);
					if (managed != null) version = managed.version();
				}
				newSelected.put(ga, version);
			}
			if (newSelected.equals(selected)) {
				converged = true;
				break;
			}
			selected = newSelected;
		}
		if (!converged) {
			throw new IllegalStateException(
				"MVS did not converge within " + MAX_ITERATIONS + " iterations");
		}

		return new Resolution(selected, warnings(catalog, selected, requests),
			requests);
	}

	// -- Walk --

	private static final class Frame {

		final GA ga;
		final String requested;
		final Set<Exclusion> exclusions;

		Frame(final GA ga, final String requested,
			final Set<Exclusion> exclusions)
		{
			this.ga = ga;
			this.requested = requested;
			this.exclusions = exclusions;
		}
	}

	private static final class State {

		final GA ga;
		final String version;
		final Set<Exclusion> exclusions;

		State(final GA ga, final String version,
			final Set<Exclusion> exclusions)
		{
			this.ga = ga;
			this.version = version;
			this.exclusions = exclusions;
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof State)) return false;
			final State other = (State) o;
			return ga.equals(other.ga) && version.equals(other.version) &&
				exclusions.equals(other.exclusions);
		}

		@Override
		public int hashCode() {
			return (ga.hashCode() * 31 + version.hashCode()) * 31 +
				exclusions.hashCode();
		}
	}

	/**
	 * One traversal from the synthetic root, honoring the current
	 * selection. Returns the requested versions per {@link GA} discovered
	 * via surviving paths.
	 */
	private Map<GA, Set<String>> walk(final ComponentCatalog catalog,
		final List<Root> roots, final Collection<Exclusion> rootExclusions,
		final Map<GA, String> selected)
	{
		final Map<GA, Set<String>> requests = new HashMap<>();
		final Deque<Frame> stack = new ArrayDeque<>();
		final Set<Exclusion> globalExclusions = rootExclusions == null ? //
			Collections.<Exclusion> emptySet() : //
			Collections.unmodifiableSet(new HashSet<>(rootExclusions));

		// Roots are seeded unconditionally: exclusions never remove an
		// explicitly selected root.
		for (final Root root : roots) {
			request(requests, root.ga(), root.version());
			stack.push(new Frame(root.ga(), root.version(), globalExclusions));
		}

		// Visited keys include the exclusion set: the same node may be
		// pruned differently on different paths, and only surviving paths
		// request.
		final Set<State> visited = new HashSet<>();

		while (!stack.isEmpty()) {
			final Frame frame = stack.pop();
			// Traverse at the *selected* version (prunes losing subtrees).
			final String version = selected.containsKey(frame.ga) ? //
				selected.get(frame.ga) : frame.requested;
			final State state = new State(frame.ga, version, frame.exclusions);
			if (!visited.add(state)) continue;

			final Release release = catalog.release(frame.ga, version);
			if (release == null || release.slim()) {
				continue; // leaf: unknown or recognition-only entry
			}

			// BOM-managed exclusions apply to this component's outgoing
			// edges wherever it appears (Maven depMgmt-exclusion semantics).
			Set<Exclusion> nodeExclusions = frame.exclusions;
			final ManagedEntry managed = catalog.managed().get(frame.ga);
			if (managed != null && !managed.exclusions().isEmpty()) {
				final Set<Exclusion> augmented = new HashSet<>(nodeExclusions);
				augmented.addAll(managed.exclusions());
				nodeExclusions = augmented;
			}

			for (final DependencyEdge edge : release.edges()) {
				if (edge.optional()) {
					continue; // optional deps of dependencies are not traversed
				}
				if (excluded(edge.ga(), nodeExclusions)) {
					continue; // pruned on this path
				}
				request(requests, edge.ga(), edge.version());
				Set<Exclusion> childExclusions = nodeExclusions;
				if (!edge.exclusions().isEmpty()) {
					final Set<Exclusion> augmented = new HashSet<>(nodeExclusions);
					augmented.addAll(edge.exclusions());
					childExclusions = augmented;
				}
				stack.push(new Frame(edge.ga(), edge.version(), childExclusions));
			}
		}
		return requests;
	}

	private static void request(final Map<GA, Set<String>> requests,
		final GA ga, final String version)
	{
		Set<String> versions = requests.get(ga);
		if (versions == null) {
			versions = new HashSet<>();
			requests.put(ga, versions);
		}
		versions.add(version);
	}

	private static boolean excluded(final GA ga,
		final Set<Exclusion> exclusions)
	{
		for (final Exclusion exclusion : exclusions) {
			if (exclusion.matches(ga)) return true;
		}
		return false;
	}

	// -- Warnings --

	private static String highest(final Collection<String> versions) {
		String best = null;
		for (final String version : versions) {
			if (best == null || MavenVersion.compare(version, best) > 0) {
				best = version;
			}
		}
		return best;
	}

	private static Integer majorOf(final String version) {
		final Matcher m = MAJOR.matcher(version);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	private static List<MediationWarning> warnings(
		final ComponentCatalog catalog, final Map<GA, String> selected,
		final Map<GA, Set<String>> requests)
	{
		final Set<MediationWarning> found = new LinkedHashSet<>();
		for (final Map.Entry<GA, Set<String>> entry : requests.entrySet()) {
			final GA ga = entry.getKey();
			final Set<String> versions = entry.getValue();
			final String chosen = selected.get(ga);
			final String highest = highest(versions);
			if (MavenVersion.compare(chosen, highest) < 0) {
				found.add(new MediationWarning(WarningType.HELD_BELOW, ga));
			}
			final Integer chosenMajor = majorOf(chosen);
			if (chosenMajor != null) {
				for (final String version : versions) {
					final Integer major = majorOf(version);
					if (major != null && !major.equals(chosenMajor)) {
						found.add(
							new MediationWarning(WarningType.MAJOR_MISALIGNMENT, ga));
						break;
					}
				}
			}
			if (catalog.release(ga, chosen) == null) {
				found.add(new MediationWarning(WarningType.MISSING_RELEASE, ga));
			}
		}
		final List<MediationWarning> sorted = new ArrayList<>(found);
		Collections.sort(sorted, new Comparator<MediationWarning>() {

			@Override
			public int compare(final MediationWarning a,
				final MediationWarning b)
			{
				final int byType = a.type().label().compareTo(b.type().label());
				if (byType != 0) return byType;
				return a.ga().compareTo(b.ga());
			}
		});
		return sorted;
	}
}
