package dev.jeswr.solid.pod

import dev.jeswr.solid.oidc.HttpClient
import dev.jeswr.solid.oidc.HttpRequest
import dev.jeswr.solid.rdf.Graph
import dev.jeswr.solid.rdf.Term
import dev.jeswr.solid.rdf.Triple
import java.net.URI
import java.util.UUID

/** The four WAC access modes. */
public enum class AccessMode {
    READ, WRITE, APPEND, CONTROL;

    internal val iri: String
        get() = Vocab.ACL_NAMESPACE + name.lowercase().replaceFirstChar { it.uppercase() }

    internal companion object {
        fun fromIri(iri: String): AccessMode? = when (iri) {
            Vocab.ACL_NAMESPACE + "Read" -> READ
            Vocab.ACL_NAMESPACE + "Write" -> WRITE
            Vocab.ACL_NAMESPACE + "Append" -> APPEND
            Vocab.ACL_NAMESPACE + "Control" -> CONTROL
            else -> null
        }
    }
}

/** The modes granted to one agent. */
public data class GrantEntry(
    /** The agent's WebID. */
    public val agent: URI,
    /** Modes in canonical order (read, write, append, control). */
    public val modes: List<AccessMode>,
)

/**
 * WAC (`.acl`) grant / revoke / enumerate, modelled on the reference TypeScript
 * kit's access layer.
 *
 * WAC semantics honoured here:
 * - The ACL location is always taken from the server's `Link rel="acl"` header
 *   — never guessed as `resource + ".acl"`.
 * - Resolution is monolithic: a resource's own ACL governs it; without one, the
 *   nearest ancestor ACL's `acl:default` rules apply.
 * - Granting on a resource with no own ACL first materialises one by copying the
 *   inherited `acl:default` rules — otherwise the fresh ACL would lock out the owner.
 * - ACL writes are conditional PUTs.
 * - ACP-governed resources (`.acr`) raise [PodException.AcpNotSupported].
 *
 * All operations need Control on the resource — use the owner's authenticated client.
 */
public class WebAccessControl(httpClient: HttpClient) {
    private val pod = SolidPodClient(httpClient)
    private val turtlePrefixes = mapOf("acl" to Vocab.ACL_NAMESPACE)

    // ACL discovery

    /** The ACL URL the server advertises for [resource] (`Link: <…>; rel="acl"`). */
    public fun aclURL(resource: URI): URI {
        val response = pod.httpClient.send(HttpRequest("HEAD", resource.toString()))
        if (!response.isSuccess && response.statusCode != 404) {
            throw PodException.forStatus(response.statusCode, resource, "ACL discovery")
        }
        return LinkHeader.targets(response.header("Link"), rel = "acl", base = resource).firstOrNull()
            ?: throw PodException.AclDiscoveryFailed(
                resource,
                "the server sent no Link rel=\"acl\" header, so the ACL location is unknown.",
            )
    }

    // Granting

    /** Grant [modes] on [resource] to [agent]. */
    public fun grant(modes: List<AccessMode>, resource: URI, agent: URI) {
        require(modes.isNotEmpty()) { "grant requires at least one mode" }
        val aclURL = aclURL(resource)
        val own = pod.readResource(aclURL)
        var graph = if (own.exists) assertWAC(own.graph, resource) else materializeOwnACL(resource, aclURL)

        val rule = findAgentOnlyRule(graph, resource, agent) ?: run {
            val (g, r) = newRule(graph, aclURL, resource, agent)
            graph = g
            r
        }
        for (mode in modes) {
            graph = graph.insert(Triple(rule, Term.IRI(Vocab.ACL_MODE), Term.IRI(mode.iri)))
        }
        pod.write(graph, aclURL, ifMatch = if (own.exists) own.etag else null, prefixes = turtlePrefixes)
    }

    /** Convenience: grant read access. */
    public fun grantRead(resource: URI, agent: URI): Unit = grant(listOf(AccessMode.READ), resource, agent)

    // Revoking

    /**
     * Remove every grant for [agent] on [resource]. When the resource has no own
     * ACL but the agent holds inherited access, an own ACL is materialised first
     * (minus the agent) so the revocation takes effect. Returns whether anything
     * changed.
     */
    public fun revoke(agent: URI, resource: URI): Boolean {
        val aclURL = aclURL(resource)
        val own = pod.readResource(aclURL)
        var graph = if (own.exists) assertWAC(own.graph, resource) else materializeOwnACL(resource, aclURL)

        var changed = false
        val agentTerm = Term.IRI(agent.toString())
        for (triple in graph.triples(predicate = Term.IRI(Vocab.ACL_AGENT), `object` = agentTerm)) {
            graph = graph.remove(triple)
            changed = true
            val rule = triple.subject
            val isEmpty = graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_AGENT)).isEmpty() &&
                graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_AGENT_CLASS)).isEmpty() &&
                graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_ORIGIN)).isEmpty() &&
                graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_AGENT_GROUP)).isEmpty()
            if (isEmpty) graph = graph.removeMatching(subject = rule)
        }
        if (!changed) return false

        pod.write(graph, aclURL, ifMatch = if (own.exists) own.etag else null, prefixes = turtlePrefixes)
        return true
    }

    // Listing

    /**
     * The per-agent grants in effect for [resource]: from its own ACL when it has
     * one, else from the nearest ancestor ACL's `acl:default` rules.
     */
    public fun grants(resource: URI): List<GrantEntry> {
        val aclURL = aclURL(resource)
        val own = pod.readResource(aclURL)
        if (own.exists) {
            return collectGrants(assertWAC(own.graph, resource), resource, Vocab.ACL_ACCESS_TO)
        }
        for (ancestor in parentContainers(resource)) {
            val ancestorACL = pod.readResource(aclURL(ancestor))
            if (!ancestorACL.exists) continue
            return collectGrants(assertWAC(ancestorACL.graph, ancestor), ancestor, Vocab.ACL_DEFAULT)
        }
        return emptyList()
    }

    /** A per-resource own-ACL audit view. */
    public class AuditEntry(public val resource: URI, public val aclURL: URI, public val grants: List<GrantEntry>)

    /**
     * Every explicit (own-ACL) grant in the container tree under [containerRoot].
     * Resources without an own ACL inherit and are omitted.
     */
    public fun allGrants(containerRoot: URI): List<AuditEntry> {
        if (!containerRoot.toString().endsWith("/")) throw PodException.NotAContainer(containerRoot)
        val results = ArrayList<AuditEntry>()
        val queue = ArrayDeque<URI>()
        queue.add(containerRoot)
        val seen = HashSet<URI>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue

            val aclURL = aclURL(current)
            val acl = pod.readResource(aclURL)
            if (acl.exists) {
                val grants = collectGrants(assertWAC(acl.graph, current), current, Vocab.ACL_ACCESS_TO)
                results.add(AuditEntry(current, aclURL, grants))
            }

            if (current.toString().endsWith("/")) {
                val listing = runCatching { pod.listContainer(current) }.getOrNull() ?: continue
                queue.addAll(listing.members)
            }
        }
        return results
    }

    // Internals

    private fun assertWAC(graph: Graph, resource: URI): Graph {
        for (triple in graph) {
            val predicateIsACP = triple.predicate.iriValue?.startsWith(Vocab.ACP_NAMESPACE) == true
            val objectIsACP = triple.`object`.iriValue?.startsWith(Vocab.ACP_NAMESPACE) == true
            if (predicateIsACP || objectIsACP) throw PodException.AcpNotSupported(resource)
        }
        return graph
    }

    private fun materializeOwnACL(resource: URI, aclURL: URI): Graph {
        for (ancestor in parentContainers(resource)) {
            val ancestorACL = pod.readResource(aclURL(ancestor))
            if (!ancestorACL.exists) continue
            val source = assertWAC(ancestorACL.graph, ancestor)

            var graph = Graph()
            var index = 0
            for (rule in authorizationRules(source)) {
                if (ancestor.toString() !in ruleTargets(rule, Vocab.ACL_DEFAULT, source)) continue
                index += 1
                val copy = Term.IRI("$aclURL#inherited-$index")
                graph = graph.insert(Triple(copy, Term.IRI(Vocab.RDF_TYPE), Term.IRI(Vocab.ACL_AUTHORIZATION)))
                graph = graph.insert(Triple(copy, Term.IRI(Vocab.ACL_ACCESS_TO), Term.IRI(resource.toString())))
                if (resource.toString().endsWith("/")) {
                    graph = graph.insert(Triple(copy, Term.IRI(Vocab.ACL_DEFAULT), Term.IRI(resource.toString())))
                }
                for (predicate in listOf(
                    Vocab.ACL_AGENT, Vocab.ACL_AGENT_CLASS, Vocab.ACL_AGENT_GROUP, Vocab.ACL_ORIGIN, Vocab.ACL_MODE,
                )) {
                    for (triple in source.triples(subject = rule, predicate = Term.IRI(predicate))) {
                        graph = graph.insert(Triple(copy, triple.predicate, triple.`object`))
                    }
                }
            }
            if (index == 0) continue
            return graph
        }
        throw PodException.AclDiscoveryFailed(
            resource,
            "no effective ACL was found on the resource or any ancestor container — refusing to " +
                "create one from scratch (it could lock out the owner or forge access). Create the " +
                "pod root ACL first.",
        )
    }

    private fun findAgentOnlyRule(graph: Graph, resource: URI, agent: URI): Term? {
        for (rule in authorizationRules(graph)) {
            if (resource.toString() !in ruleTargets(rule, Vocab.ACL_ACCESS_TO, graph)) continue
            val agents = graph.iriObjects(rule, Term.IRI(Vocab.ACL_AGENT))
            if (agents != listOf(agent.toString())) continue
            if (graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_AGENT_CLASS)).isNotEmpty()) continue
            if (graph.triples(subject = rule, predicate = Term.IRI(Vocab.ACL_ORIGIN)).isNotEmpty()) continue
            return rule
        }
        return null
    }

    private fun newRule(graph: Graph, aclURL: URI, resource: URI, agent: URI): Pair<Graph, Term> {
        val rule = Term.IRI("$aclURL#grant-${UUID.randomUUID()}")
        var g = graph
            .insert(Triple(rule, Term.IRI(Vocab.RDF_TYPE), Term.IRI(Vocab.ACL_AUTHORIZATION)))
            .insert(Triple(rule, Term.IRI(Vocab.ACL_ACCESS_TO), Term.IRI(resource.toString())))
        if (resource.toString().endsWith("/")) {
            g = g.insert(Triple(rule, Term.IRI(Vocab.ACL_DEFAULT), Term.IRI(resource.toString())))
        }
        g = g.insert(Triple(rule, Term.IRI(Vocab.ACL_AGENT), Term.IRI(agent.toString())))
        return g to rule
    }

    private fun authorizationRules(graph: Graph): List<Term> =
        graph.subjects(Term.IRI(Vocab.RDF_TYPE), Term.IRI(Vocab.ACL_AUTHORIZATION))
            .sortedBy { sortKey(it) }

    private fun sortKey(term: Term): String = when (term) {
        is Term.IRI -> term.value
        is Term.BlankNode -> term.id
        is Term.LiteralTerm -> term.literal.lexicalForm
    }

    private fun ruleTargets(rule: Term, predicate: String, graph: Graph): List<String> =
        graph.iriObjects(rule, Term.IRI(predicate))

    private fun collectGrants(graph: Graph, target: URI, predicate: String): List<GrantEntry> {
        val byAgent = HashMap<URI, MutableSet<AccessMode>>()
        for (rule in authorizationRules(graph)) {
            if (target.toString() !in ruleTargets(rule, predicate, graph)) continue
            val modes = graph.iriObjects(rule, Term.IRI(Vocab.ACL_MODE)).mapNotNull { AccessMode.fromIri(it) }
            if (modes.isEmpty()) continue
            for (agentIRI in graph.iriObjects(rule, Term.IRI(Vocab.ACL_AGENT))) {
                val agent = runCatching { URI(agentIRI) }.getOrNull() ?: continue
                byAgent.getOrPut(agent) { mutableSetOf() }.addAll(modes)
            }
        }
        return byAgent
            .map { (agent, modes) -> GrantEntry(agent, AccessMode.entries.filter { it in modes }) }
            .sortedBy { it.agent.toString() }
    }

    private fun parentContainers(resource: URI): List<URI> = pod.ancestors(resource).drop(1)
}
